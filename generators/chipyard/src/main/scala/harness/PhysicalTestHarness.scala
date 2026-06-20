package chipyard.harness

import chisel3._

import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyRawModuleImp}

import chipyard.ExtTLMem

class PhysicalTestHarness(override implicit val p: Parameters) extends LazyModule {
  override lazy val module = new PhysicalTestHarnessImp(this)
}

class PhysicalTestHarnessImp(_outer: PhysicalTestHarness)
    extends LazyRawModuleImp(_outer) with HasHarnessInstantiators {
  override def provideImplicitClockToLazyChildren = true

  require(_outer.p(ExtTLMem).nonEmpty,
    "PhysicalTestHarness requires ExtTLMem; add chipyard.config.WithTLBackingMemory")

  val clock = IO(Input(Clock())).suggestName("clock")
  val reset = IO(Input(AsyncReset())).suggestName("reset")
  val io = IO(new Bundle {
    val success = Output(Bool())
  })

  override val supportsMultiChip = true

  def referenceClockFreqMHz: Double = getHarnessBinderClockFreqMHz
  def referenceClock: Clock = clock
  def referenceReset: Reset = reset
  def success: Bool = successFn(chiptopSuccess)

  childClock := referenceClock
  childReset := referenceReset
  io.success := success

  instantiateChipTops()
}
