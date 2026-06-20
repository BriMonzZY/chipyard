package chipyard.harness

import chisel3._
import chisel3.experimental.Analog
import chisel3.reflect.DataMirror

import org.chipsalliance.cde.config.{Config, Parameters}
import org.chipsalliance.diplomacy.ValName
import org.chipsalliance.diplomacy.lazymodule.{InModuleBody, LazyModule, LazyModuleImp}
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy.{AddressSet, IdRange, RegionType, TransferSizes}
import freechips.rocketchip.subsystem.{CacheBlockBytes, MemoryPortParams}
import freechips.rocketchip.tilelink._

import chipyard.ExtTLMem
import chipyard.iobinders._
import testchipip.util.ClockedIO

class PhysicalTLMemToAXI4(params: MemoryPortParams)(implicit p: Parameters) extends LazyModule {
  private val master = params.master
  private val addressSets = AddressSet.misaligned(master.base, master.size)

  val tlClientNode = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1(
    name = "physical_axi4_mem",
    sourceId = IdRange(0, 1 << master.idBits)
  )))))

  val axi4SlaveNode = AXI4SlaveNode(Seq.tabulate(params.nMemoryChannels) { channel =>
    val filter = AddressSet(channel * master.maxXferBytes, ~((params.nMemoryChannels - 1) * master.maxXferBytes))
    AXI4SlavePortParameters(
      slaves = Seq(AXI4SlaveParameters(
        address = addressSets.flatMap(_.intersect(filter)),
        regionType = RegionType.UNCACHED,
        executable = master.executable,
        supportsRead = TransferSizes(1, master.maxXferBytes),
        supportsWrite = TransferSizes(1, master.maxXferBytes),
        interleavedId = Some(0))),
      beatBytes = master.beatBytes)
  })

  axi4SlaveNode :=
    AXI4UserYanker() :=
    AXI4Deinterleaver(p(CacheBlockBytes)) :=
    AXI4IdIndexer(master.idBits) :=
    TLToAXI4(adapterName = Some("mem")) :=
    TLWidthWidget(master.beatBytes) :=
    tlClientNode

  val axi4 = InModuleBody { axi4SlaveNode.makeIOs()(ValName("axi4_mem")) }
  val tl = InModuleBody { tlClientNode.makeIOs()(ValName("tl_mem")) }

  lazy val module = new LazyModuleImp(this)
}

class WithPhysicalAXI4MemFromTLMem extends HarnessBinder({
  case (th: PhysicalTestHarnessImp, port: TLMemPort, chipId: Int) => {
    implicit val p: Parameters = th.p
    val memParams = p(ExtTLMem).getOrElse {
      throw new IllegalArgumentException("PhysicalTestHarness requires ExtTLMem; add chipyard.config.WithTLBackingMemory")
    }
    val converter = LazyModule(new PhysicalTLMemToAXI4(memParams))
    withClock(th.referenceClock) { Module(converter.module) }

    require(converter.tl.size == port.io.size,
      s"Physical AXI4 memory converter produced ${converter.tl.size} TL ports, but ChipTop exposed ${port.io.size}")
    converter.tl <> port.io

    converter.axi4.zipWithIndex.foreach { case (axi, i) =>
      val top = IO(new ClockedIO(DataMirror.internal.chiselTypeClone[AXI4Bundle](axi))).suggestName(s"axi4_mem_${i}")
      top.bits <> axi
      top.clock := th.referenceClock
    }
  }
})

class WithPhysicalClockAndReset extends HarnessBinder({
  case (th: PhysicalTestHarnessImp, port: ClockPort, chipId: Int) => {
    port.io := th.harnessClockInstantiator.requestClockMHz(s"clock_${port.freqMHz}MHz", port.freqMHz)
  }
  case (th: PhysicalTestHarnessImp, port: ResetPort, chipId: Int) => {
    port.io := th.referenceReset.asAsyncReset
  }
  case (th: PhysicalTestHarnessImp, port: DebugResetPort, chipId: Int) => {
    port.io := th.referenceReset
  }
  case (th: PhysicalTestHarnessImp, port: JTAGResetPort, chipId: Int) => {
    port.io := th.referenceReset
  }
})

class WithPhysicalSuccess extends HarnessBinder({
  case (th: PhysicalTestHarnessImp, port: SuccessPort, chipId: Int) => {
    when (port.io) { th.chiptopSuccess(chipId) := true.B }
  }
})

class WithPhysicalPortPunchthrough extends HarnessBinder({
  case (th: PhysicalTestHarnessImp, port: GPIOPort, chipId: Int) => {
    val top = IO(Analog(port.io.getWidth.W)).suggestName(s"gpio_${chipId}_${port.gpioId}_${port.pinId}")
    top <> port.io
  }
  case (th: PhysicalTestHarnessImp, port: GPIOPinsPort, chipId: Int) => {
    val top = IO(chiselTypeOf(port.io)).suggestName(s"gpio_${chipId}_${port.gpioId}")
    top <> port.io
  }
  case (th: PhysicalTestHarnessImp, port: I2CPort, chipId: Int) => {
    val top = IO(chiselTypeOf(port.io)).suggestName(s"i2c_${chipId}")
    top <> port.io
  }
  case (th: PhysicalTestHarnessImp, port: UARTPort, chipId: Int) => {
    val top = IO(chiselTypeOf(port.io)).suggestName(s"uart_${chipId}_${port.uartNo}")
    top <> port.io
  }
  case (th: PhysicalTestHarnessImp, port: SPIFlashPort, chipId: Int) => {
    val top = IO(chiselTypeOf(port.io)).suggestName(s"spi_flash_${chipId}_${port.spiId}")
    top <> port.io
  }
  case (th: PhysicalTestHarnessImp, port: SPIPort, chipId: Int) => {
    val top = IO(chiselTypeOf(port.io)).suggestName(s"spi_${chipId}")
    top <> port.io
  }
  case (th: PhysicalTestHarnessImp, port: BlockDevicePort, chipId: Int) => {
    val top = IO(chiselTypeOf(port.io)).suggestName(s"blockdev_${chipId}")
    top <> port.io
  }
  case (th: PhysicalTestHarnessImp, port: NICPort, chipId: Int) => {
    val top = IO(chiselTypeOf(port.io)).suggestName(s"nic_${chipId}")
    top <> port.io
  }
  case (th: PhysicalTestHarnessImp, port: AXI4MemPort, chipId: Int) => {
    val top = IO(chiselTypeOf(port.io)).suggestName(s"axi4_existing_mem_${chipId}")
    top <> port.io
  }
  case (th: PhysicalTestHarnessImp, port: AXI4MMIOPort, chipId: Int) => {
    val top = IO(chiselTypeOf(port.io)).suggestName(s"axi4_mmio_${chipId}")
    top <> port.io
  }
  case (th: PhysicalTestHarnessImp, port: AXI4PBusPort, chipId: Int) => {
    val top = IO(chiselTypeOf(port.io)).suggestName(s"axi4_pbus_${chipId}")
    top <> port.io
  }
  case (th: PhysicalTestHarnessImp, port: AXI4InPort, chipId: Int) => {
    val top = IO(chiselTypeOf(port.io)).suggestName(s"axi4_fbus_${chipId}")
    top <> port.io
  }
  case (th: PhysicalTestHarnessImp, port: ExtIntPort, chipId: Int) => {
    val top = IO(chiselTypeOf(port.io)).suggestName(s"ext_interrupts_${chipId}")
    port.io := top
  }
  case (th: PhysicalTestHarnessImp, port: DMIPort, chipId: Int) => {
    val top = IO(chiselTypeOf(port.io)).suggestName(s"dmi_${chipId}")
    top <> port.io
  }
  case (th: PhysicalTestHarnessImp, port: JTAGPort, chipId: Int) => {
    val top = IO(chiselTypeOf(port.io)).suggestName(s"jtag_${chipId}")
    top <> port.io
  }
  case (th: PhysicalTestHarnessImp, port: SerialTLPort, chipId: Int) => {
    val top = IO(chiselTypeOf(port.io)).suggestName(s"serial_tl_${chipId}_${port.portId}")
    top <> port.io
  }
  case (th: PhysicalTestHarnessImp, port: ChipIdPort, chipId: Int) => {
    val top = IO(chiselTypeOf(port.io)).suggestName(s"chip_id_${chipId}")
    port.io := top
  }
  case (th: PhysicalTestHarnessImp, port: UARTTSIPort, chipId: Int) => {
    val top = IO(chiselTypeOf(port.io)).suggestName(s"uart_tsi_${chipId}")
    top <> port.io
  }
  case (th: PhysicalTestHarnessImp, port: TracePort, chipId: Int) => {
    val top = IO(chiselTypeOf(port.io)).suggestName(s"trace_${chipId}")
    top <> port.io
  }
  case (th: PhysicalTestHarnessImp, port: CustomBootPort, chipId: Int) => {
    val top = IO(chiselTypeOf(port.io)).suggestName(s"custom_boot_${chipId}")
    port.io := top
  }
  case (th: PhysicalTestHarnessImp, port: ClockTapPort, chipId: Int) => {
    val top = IO(Output(Clock())).suggestName(s"clock_tap_${chipId}")
    top := port.io
  }
  case (th: PhysicalTestHarnessImp, port: GCDBusyPort, chipId: Int) => {
    val top = IO(chiselTypeOf(port.io)).suggestName(s"gcd_busy_${chipId}")
    top := port.io
  }
  case (th: PhysicalTestHarnessImp, port: OffchipSelPort, chipId: Int) => {
    val top = IO(chiselTypeOf(port.io)).suggestName(s"offchip_sel_${chipId}")
    port.io := top
  }
  case (th: PhysicalTestHarnessImp, port: CTCPort, chipId: Int) => {
    val top = IO(chiselTypeOf(port.io)).suggestName(s"ctc_${chipId}_${port.portId}")
    top <> port.io
  }
  case (th: PhysicalTestHarnessImp, port: TLMemPort, chipId: Int) =>
  case (th: PhysicalTestHarnessImp, port, chipId) => {
    throw new IllegalArgumentException(s"PhysicalTestHarness has no punch-through binder for ${port.getClass.getName}")
  }
})

class WithPhysicalHarnessBinders extends Config(
  new WithPhysicalAXI4MemFromTLMem ++
  new WithPhysicalClockAndReset ++
  new WithPhysicalSuccess ++
  new WithPhysicalPortPunchthrough
)
