package chipyard

import org.chipsalliance.cde.config.{Config}
import saturn.common.VectorParams


// class RVDLARocketConfig extends Config(
//   new rvdla.WithRVDLA ++
//   new freechips.rocketchip.rocket.WithNHugeCores(1) ++
//   new chipyard.config.WithSystemBusWidth(256) ++
//   new chipyard.config.AbstractConfig)


// MBUS/SBUS宽度256
// class RVDLARocketConfig extends Config(
//   new rvdla.WithRVDLA ++
//   new freechips.rocketchip.rocket.WithNHugeCores(1) ++
//   new chipyard.config.WithSystemBusWidth(256) ++
//   new chipyard.config.WithMemoryBusWidth(256) ++
//   new chipyard.config.WithInclusiveCacheWriteBytes(32)++
//   new chipyard.config.AbstractConfig)

// MBUS/SBUS宽度256，CachelineSize 128B
// class RVDLARocketConfig extends Config(
//   new rvdla.WithRVDLA ++
//   new freechips.rocketchip.rocket.WithL1DCacheSets(32) ++ // L1D$ 的CacheSetSize不能跨过4KiB边界
//   new freechips.rocketchip.rocket.WithNHugeCores(1) ++
//   new chipyard.config.WithSystemBusWidth(256) ++
//   new chipyard.config.WithMemoryBusWidth(256) ++
//   new chipyard.config.WithInclusiveCacheWriteBytes(32) ++
//   new freechips.rocketchip.subsystem.WithCacheBlockBytes(128) ++ // 将CachelineSize调整为128B
//   new chipyard.config.AbstractConfig)


// MBUS/SBUS宽度256，CachelineSize 512B
class RVDLARocketConfig extends Config(
  new rvdla.WithRVDLA ++
  new freechips.rocketchip.rocket.WithoutVM ++ // 取消虚拟内存支持，解除 cacheset 4KiB的限制
  new freechips.rocketchip.rocket.WithL1DCacheSets(16) ++
  new freechips.rocketchip.rocket.WithL1DCacheWays(4) ++
  new freechips.rocketchip.rocket.WithL1ICacheSets(16) ++
  new freechips.rocketchip.rocket.WithL1ICacheWays(4) ++
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.WithSystemBusWidth(256) ++
  new chipyard.config.WithMemoryBusWidth(256) ++
  new chipyard.config.WithInclusiveCacheWriteBytes(32) ++
  new freechips.rocketchip.subsystem.WithCacheBlockBytes(512) ++
  new chipyard.config.AbstractConfig)


// MBUS/SBUS宽度256
class RVDLASmallBoomConfig extends Config(
  new rvdla.WithRVDLA ++
  new boom.v3.common.WithNSmallBooms(1) ++
  new chipyard.config.WithSystemBusWidth(256) ++
  new chipyard.config.WithMemoryBusWidth(256) ++
  new chipyard.config.WithInclusiveCacheWriteBytes(32) ++
  new chipyard.config.AbstractConfig)


class RVDLAShuttleSaturnConfig extends Config(
  new rvdla.WithRVDLA ++
  new chipyard.config.WithSystemBusWidth(256) ++
  new chipyard.config.WithMemoryBusWidth(256) ++
  new chipyard.config.WithInclusiveCacheWriteBytes(32)++
  new saturn.shuttle.WithShuttleVectorUnit(vLen = 256, dLen = 256, VectorParams.refParams, mLen = Option(256)) ++
  new shuttle.common.WithTCM(address = 0x70000000L, size = 2L << 20, banks = 2) ++
  new shuttle.common.WithShuttleTileBeatBytes(32) ++
  new shuttle.common.WithNShuttleCores(1) ++
  new freechips.rocketchip.subsystem.WithoutTLMonitors ++
  new chipyard.config.AbstractConfig)

