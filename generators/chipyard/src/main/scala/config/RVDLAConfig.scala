package chipyard

import org.chipsalliance.cde.config.{Config}


class RVDLARocketConfig extends Config(
  new rvdla.WithRVDLA ++
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.WithSystemBusWidth(256) ++
  new chipyard.config.AbstractConfig)

// class RVDLARocketConfig extends Config(
//   new rvdla.WithRVDLA ++
//   new freechips.rocketchip.rocket.WithNHugeCores(1) ++
//   new chipyard.config.WithSystemBusWidth(256) ++
//   new chipyard.config.WithMemoryBusWidth(256) ++
//   new chipyard.config.AbstractConfig)

// class RVDLADirectAXIRocketConfig extends Config(
//   new chipyard.harness.WithSimAXIMem ++
//   new rvdla.WithRVDLADirectAXI ++
//   new rvdla.WithExtMemAXI4Width(bitWidth = 256, maxXferBytes = 8192) ++
//   new chipyard.config.WithSystemBusWidth(64) ++
//   new freechips.rocketchip.rocket.WithNHugeCores(1) ++
//   new chipyard.config.AbstractConfig)
