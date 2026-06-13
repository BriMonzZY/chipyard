package chipyard

import org.chipsalliance.cde.config.{Config}


// class RVDLARocketConfig extends Config(
//   new rvdla.WithRVDLA ++
//   new freechips.rocketchip.rocket.WithNHugeCores(1) ++
//   new chipyard.config.WithSystemBusWidth(256) ++
//   new chipyard.config.AbstractConfig)


// InclusiveCacheWriteBytes需要小于等于SystemBus/8以及MemoryBus/8
class RVDLARocketConfig extends Config(
  new rvdla.WithRVDLA ++
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.WithSystemBusWidth(256) ++
  new chipyard.config.WithMemoryBusWidth(256) ++
  new chipyard.config.WithInclusiveCacheWriteBytes(32)++
  new chipyard.config.AbstractConfig)

// class RVDLARocketConfig extends Config(
//   new rvdla.WithRVDLA ++
//   new freechips.rocketchip.rocket.WithNHugeCores(1) ++
//   new chipyard.config.WithSystemBusWidth(512) ++
//   new chipyard.config.WithMemoryBusWidth(512) ++
//   new chipyard.config.WithInclusiveCacheWriteBytes(64)++
//   new chipyard.config.AbstractConfig)
