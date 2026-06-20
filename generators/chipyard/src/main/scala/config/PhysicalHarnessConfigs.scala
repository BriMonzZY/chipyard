package chipyard.config

import sys.process._

import org.chipsalliance.cde.config.Config
import freechips.rocketchip.devices.tilelink.BootROMLocated
import freechips.rocketchip.subsystem.SystemBusKey
import freechips.rocketchip.util.SystemFileName
import sifive.blocks.devices.spi.{PeripherySPIKey, SPIParams}
import sifive.blocks.devices.uart.{PeripheryUARTKey, UARTParams}
import testchipip.serdes.SerialTLKey

class WithPhysicalSdbootPeripherals extends Config((site, here, up) => {
  case PeripheryUARTKey => List(UARTParams(address = BigInt(0x64000000L)))
  case PeripherySPIKey => List(SPIParams(rAddress = BigInt(0x64001000L)))
})

class WithPhysicalSdbootBootROM extends Config((site, here, up) => {
  case BootROMLocated(x) => up(BootROMLocated(x)).map { p =>
    val uartAddrs = site(PeripheryUARTKey).map(_.address)
    val spiAddrs = site(PeripherySPIKey).map(_.rAddress)
    require(uartAddrs.contains(BigInt(0x64000000L)),
      "Physical sdboot requires a UART at 0x64000000; add chipyard.config.WithPhysicalSdbootPeripherals")
    require(spiAddrs.contains(BigInt(0x64001000L)),
      "Physical sdboot requires an SPI controller at 0x64001000; add chipyard.config.WithPhysicalSdbootPeripherals")

    val freqMHz = (site(SystemBusKey).dtsFrequency.get / (1000 * 1000)).toLong
    val make = s"make -C fpga/src/main/resources/vcu118/sdboot PBUS_CLK=${freqMHz} bin"
    require(make.! == 0, "Failed to build physical sdboot bootrom")
    p.copy(
      hang = 0x10000,
      contentFileName = SystemFileName("./fpga/src/main/resources/vcu118/sdboot/build/sdboot.bin"))
  }
})

class WithPhysicalHarness(freqMHz: Double = 100.0, memSize: BigInt = BigInt(1) << 30) extends Config(
  new chipyard.harness.WithPhysicalHarnessBinders ++
  new chipyard.harness.WithAllClocksFromHarnessClockInstantiator ++
  new chipyard.clocking.WithPassthroughClockGenerator ++
  new chipyard.harness.WithHarnessBinderClockFreqMHz(freqMHz) ++
  new chipyard.config.WithUniformBusFrequencies(freqMHz) ++
  new chipyard.config.WithTLBackingMemory ++
  new freechips.rocketchip.subsystem.WithExtMemSize(memSize) ++
  new WithPhysicalSdbootPeripherals ++
  new WithPhysicalSdbootBootROM ++
  new Config((site, here, up) => {
    case SerialTLKey => Nil
  }) ++
  new freechips.rocketchip.subsystem.WithoutTLMonitors ++
  new freechips.rocketchip.subsystem.WithNMemoryChannels(1)
)

class WithPhysicalHarnessNoDebug(freqMHz: Double = 100.0, memSize: BigInt = BigInt(1) << 30) extends Config(
  new chipyard.config.WithNoDebug ++
  new WithPhysicalHarness(freqMHz, memSize)
)
