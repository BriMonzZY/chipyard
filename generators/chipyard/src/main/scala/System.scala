//******************************************************************************
// Copyright (c) 2019 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

package chipyard

import chisel3._

import org.chipsalliance.cde.config.{Parameters, Field}
import org.chipsalliance.diplomacy.lazymodule.InModuleBody
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.resources.SimpleBus
import freechips.rocketchip.util.{DontTouch}

// ---------------------------------------------------------------------
// Base system that uses the debug test module (dtm) to bringup the core
// ---------------------------------------------------------------------

/**
 * Base top with periphery devices and ports, and a BOOM + Rocket subsystem
 */
class ChipyardSystem(implicit p: Parameters) extends ChipyardSubsystem
  with HasAsyncExtInterrupts
  with CanHaveMasterTLMemPort // export TL port for outer memory
  with CanHaveMasterAXI4MemPort // expose AXI port for outer mem
  with CanHaveMasterAXI4MMIOPort
  with CanHaveMasterAXI4PBusPort
  with CanHaveSlaveAXI4Port
{

  val bootROM  = p(BootROMLocated(location)).map { BootROM.attach(_, this, CBUS) }
  val maskROMs = p(MaskROMLocated(location)).map { MaskROM.attach(_, this, CBUS) }

  override lazy val module = new ChipyardSystemModule(this)
}

/**
 * Base top module implementation with periphery devices and ports, and a BOOM + Rocket subsystem
 */
class ChipyardSystemModule(_outer: ChipyardSystem) extends ChipyardSubsystemModuleImp(_outer)
  with HasRTCModuleImp
  with HasExtInterruptsModuleImp
  with DontTouch

// ------------------------------------
// TL Mem Port Mixin
// ------------------------------------

// Similar to ExtMem but instantiates a TL mem port
case object ExtTLMem extends Field[Option[MemoryPortParams]](None)

// Physical harness PBUS AXI4 master port
case object ExtPBusAXI4 extends Field[Option[MasterPortParams]](None)

/** Adds a port to the system intended to master an TL DRAM controller. */
trait CanHaveMasterTLMemPort { this: BaseSubsystem =>

  require(!(p(ExtTLMem).nonEmpty && p(ExtMem).nonEmpty),
    "Can only have 1 backing memory port. Use ExtTLMem for a TL memory port or ExtMem for an AXI memory port.")

  private val memPortParamsOpt = p(ExtTLMem)
  private val portName = "tl_mem"
  private val device = new MemoryDevice
  private val idBits = memPortParamsOpt.map(_.master.idBits).getOrElse(1)
  private val mbus = tlBusWrapperLocationMap.lift(MBUS).getOrElse(locateTLBusWrapper(SBUS))

  val memTLNode = TLManagerNode(memPortParamsOpt.map({ case MemoryPortParams(memPortParams, nMemoryChannels, _) =>
    Seq.tabulate(nMemoryChannels) { channel =>
      val base = AddressSet.misaligned(memPortParams.base, memPortParams.size)
      val filter = AddressSet(channel * mbus.blockBytes, ~((nMemoryChannels-1) * mbus.blockBytes))

     TLSlavePortParameters.v1(
       managers = Seq(TLSlaveParameters.v1(
         address            = base.flatMap(_.intersect(filter)),
         resources          = device.reg,
         regionType         = RegionType.UNCACHED, // cacheable
         executable         = true,
         supportsGet        = TransferSizes(1, mbus.blockBytes),
         supportsPutFull    = TransferSizes(1, mbus.blockBytes),
         supportsPutPartial = TransferSizes(1, mbus.blockBytes))),
         beatBytes = memPortParams.beatBytes)
    }
  }).toList.flatten)

  // disable inwards monitors from node since the class with this trait (i.e. DigitalTop)
  // doesn't provide an implicit clock to those monitors
  mbus.coupleTo(s"memory_controller_port_named_$portName") {
    (DisableMonitors { implicit p => memTLNode :*= TLBuffer() }
      :*= TLSourceShrinker(1 << idBits)
      :*= TLWidthWidget(mbus.beatBytes)
      :*= _)
  }

  val mem_tl = InModuleBody { memTLNode.makeIOs() }
}

/** Adds an AXI4 port from PBUS, with the SoC acting as AXI4 master. */
trait CanHaveMasterAXI4PBusPort { this: BaseSubsystem =>
  private val pbusPortParamsOpt = p(ExtPBusAXI4)
  private val portName = "pbus_axi4"
  private val device = new SimpleBus("pbus-axi4", Nil)
  private lazy val pbus = locateTLBusWrapper(PBUS)

  val pbusAXI4Node = AXI4SlaveNode(
    pbusPortParamsOpt.map(params =>
      AXI4SlavePortParameters(
        slaves = Seq(AXI4SlaveParameters(
          address       = AddressSet.misaligned(params.base, params.size),
          resources     = device.ranges,
          regionType    = RegionType.UNCACHED,
          executable    = params.executable,
          supportsWrite = TransferSizes(1, params.maxXferBytes),
          supportsRead  = TransferSizes(1, params.maxXferBytes))),
        beatBytes = params.beatBytes)).toSeq)

  pbusPortParamsOpt.foreach { params =>
    pbus.coupleTo(s"port_named_$portName") {
      (pbusAXI4Node
        := AXI4Buffer()
        := AXI4UserYanker()
        := AXI4Deinterleaver(pbus.blockBytes)
        := AXI4IdIndexer(params.idBits)
        := TLToAXI4(adapterName = Some("pbus"))
        := TLWidthWidget(pbus.beatBytes)
        := _)
    }
  }

  val pbus_axi4 = InModuleBody { pbusAXI4Node.makeIOs() }
}
