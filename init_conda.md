# build-setup.sh 初始化 Conda 环境过程分析

本文分析的是在 `/home/brimon/chipyard` 中运行 `build-setup.sh` 后，第 1 步 `Conda environment setup` 具体做了什么，以及当前这次运行是如何创建 Conda 环境的。

分析依据来自当前工作区中的以下文件和状态：

- `build-setup.sh`：第 1 步的实际实现位于 153-233 行。
- `scripts/build-setup.sh`：内容与顶层 `build-setup.sh` 一致。
- `scripts/generate-conda-lockfiles.sh`：用于重新生成 Conda lock 文件。
- `conda-reqs/*.yaml` 与 `conda-reqs/conda-lock-reqs/*.conda-lock.yml`：Conda 环境输入和锁定后的完整依赖。
- `.conda-lock-env/conda-meta/history`：辅助环境的创建记录。
- `.conda-env/conda-meta/history`：主 Chipyard Conda 环境的创建记录。
- `env.sh`：脚本写入的后续激活入口。

注意：当前 `build-setup.log` 从 `STEP 2: Initializing Chipyard submodules` 开始，说明它很可能被后续一次跳过 Step 1 的运行覆盖，不能直接作为本次 Step 1 输出日志使用。本文对实际发生过程的判断主要基于脚本逻辑、Conda history、`env.sh` 和相关文件时间戳。

## 1. 进入 Step 1 前的全局初始化

`build-setup.sh` 一开始做了几个全局动作：

1. 开启错误处理：

   ```bash
   set -e
   set -o pipefail
   ```

   这表示普通阶段中只要命令失败，脚本就会停止；管道中任意命令失败也会使管道失败。

2. 定位 Chipyard 仓库根目录：

   ```bash
   CYDIR=$(git rev-parse --show-toplevel)
   ```

   在当前运行中，`CYDIR` 是：

   ```text
   /home/brimon/chipyard
   ```

3. 加载工具函数：

   ```bash
   source $CYDIR/scripts/utils.sh
   common_setup
   ```

   `utils.sh` 主要提供：

   - `error` / `die`：错误输出和退出。
   - `replace_content`：调用 `scripts/replace-content.py`，在 `env.sh` 中按 marker 块替换内容。
   - `common_setup`：设置 `READLINK` 等兼容变量。

4. 设置默认参数：

   ```bash
   TOOLCHAIN_TYPE="riscv-tools"
   USE_UNPINNED_DEPS=false
   USE_LEAN_CONDA=false
   GLOBAL_ENV_NAME=""
   ```

   在没有传入特殊参数时，后续会创建：

   - 工具链类型：`riscv-tools`
   - 完整 Conda 环境，而不是 lean 环境。
   - 本地 prefix 环境：`/home/brimon/chipyard/.conda-env`
   - 辅助 conda-lock 环境：`/home/brimon/chipyard/.conda-lock-env`

5. 解析跳过参数。

   Step 1 只有在没有传入 `--skip-conda` 或 `-s 1` 时才会运行：

   ```bash
   if run_step "1"; then
       begin_step "1" "Conda environment setup"
       ...
   fi
   ```

6. 在进入 Step 1 前，脚本先写入 `env.sh` 的 Chipyard 根目录辅助变量：

   ```bash
   replace_content env.sh cy-dir-helper "CY_DIR=${CYDIR}"
   ```

   当前生成结果为：

   ```bash
   # >>> cy-dir-helper initialize >>>
   CY_DIR=/home/brimon/chipyard
   # <<< cy-dir-helper initialize <<<
   ```

## 2. Step 1 的目标

Step 1 的目标不是直接用 `conda env create -f xxx.yaml` 创建环境，而是采用两阶段流程：

1. 先创建一个很小的辅助环境 `.conda-lock-env`，只用来运行固定版本的 `conda-lock`。
2. 再使用 `conda-lock install` 从 `.conda-lock.yml` 锁文件创建真正的 Chipyard 主环境 `.conda-env`。

这样做的好处是：

- 主环境安装基于 lock 文件，包版本和校验和是确定的。
- 避免每次直接从 YAML 重新求解依赖导致结果漂移。
- 只有在需要时才重新生成 lock 文件。

整体流程可简化为：

```text
build-setup.sh
  |
  |-- 写 env.sh 中的 CY_DIR
  |
  |-- Step 1: Conda environment setup
        |
        |-- 选择 lock 文件
        |-- 检查 .conda-lock-env / .conda-env 是否已存在
        |-- 创建并激活 .conda-lock-env
        |-- 必要时重新生成 lock 文件
        |-- 用 conda-lock install 创建 .conda-env
        |-- 激活 .conda-env
        |-- 把激活逻辑写入 env.sh
```

## 3. 选择 Conda 需求目录和 lock 文件

Step 1 开始后，脚本先设置 Conda 需求目录：

```bash
CONDA_REQS=$CYDIR/conda-reqs
CONDA_LOCK_REQS=$CONDA_REQS/conda-lock-reqs
```

在当前仓库中对应：

```text
/home/brimon/chipyard/conda-reqs
/home/brimon/chipyard/conda-reqs/conda-lock-reqs
```

随后根据是否使用 lean 环境选择 lock 文件。

默认 `USE_LEAN_CONDA=false`，所以使用完整环境 lock 文件：

```bash
LOCKFILE=$CONDA_LOCK_REQS/conda-requirements-riscv-tools-linux-64.conda-lock.yml
```

即：

```text
/home/brimon/chipyard/conda-reqs/conda-lock-reqs/conda-requirements-riscv-tools-linux-64.conda-lock.yml
```

如果传入 `--use-lean-conda`，则使用：

```text
/home/brimon/chipyard/conda-reqs/conda-lock-reqs/conda-requirements-riscv-tools-linux-64-lean.conda-lock.yml
```

两种 lock 文件的来源不同：

- 完整环境 lock 文件由这 4 个 YAML 合并求解：

  ```text
  conda-reqs/chipyard-base.yaml
  conda-reqs/chipyard-extended.yaml
  conda-reqs/docs.yaml
  conda-reqs/riscv-tools.yaml
  ```

- lean 环境 lock 文件由这 3 个 YAML 合并求解：

  ```text
  conda-reqs/chipyard-base.yaml
  conda-reqs/docs.yaml
  conda-reqs/riscv-tools.yaml
  ```

当前完整 lock 文件中记录了：

- `metadata.sources` 包含上述 4 个 YAML。
- `metadata.channels` 包含 `ucb-bar`、`conda-forge`、`litex-hub`。
- `metadata.platforms` 是 `linux-64`。
- 包条目中有 422 个 Conda 包和 16 个 pip 包。

当前 lean lock 文件中记录了：

- `metadata.sources` 包含 `chipyard-base.yaml`、`docs.yaml`、`riscv-tools.yaml`。
- Conda 包数量为 209 个。
- 当前没有 pip 包条目。

## 4. 防止覆盖已有 Conda 环境

脚本接着设置辅助环境路径：

```bash
CONDA_LOCK_ENV_PATH=$CYDIR/.conda-lock-env
```

然后检查两个本地环境目录是否已经存在：

```bash
if [ -d $CONDA_LOCK_ENV_PATH ] || [ -d "$CYDIR/.conda-env" ]; then
    echo "Error: Conda environment directories already exist! Delete them before trying to recreate the conda environment or `source env.sh` and skip this step with `-s 1`." >&2
    exit 1
fi
```

也就是说，如果当前目录下已经存在下面任意一个目录，重新运行 Step 1 会直接失败：

```text
/home/brimon/chipyard/.conda-lock-env
/home/brimon/chipyard/.conda-env
```

当前这两个目录都已经存在：

```text
.conda-lock-env  创建/更新时间：2026-06-18 18:42:16 +0800
.conda-env       创建/更新时间：2026-06-18 18:50:10 +0800
```

因此，如果现在不删除它们而再次运行完整的 `./build-setup.sh`，Step 1 会报错。后续复用现有环境时应执行：

```bash
source env.sh
./build-setup.sh -s 1
```

或使用等价的：

```bash
./build-setup.sh --skip-conda
```

## 5. 创建辅助环境 `.conda-lock-env`

脚本先删除辅助环境路径，然后创建一个只用于运行 `conda-lock` 的环境：

```bash
rm -rf $CONDA_LOCK_ENV_PATH &&
conda create -y -p $CONDA_LOCK_ENV_PATH -c conda-forge $(grep "conda-lock" $CONDA_REQS/chipyard-base.yaml | sed 's/^ \+-//') &&
source $(conda info --base)/etc/profile.d/conda.sh &&
conda activate $CONDA_LOCK_ENV_PATH
```

这里最关键的是这一段：

```bash
$(grep "conda-lock" $CONDA_REQS/chipyard-base.yaml | sed 's/^ \+-//')
```

当前 `conda-reqs/chipyard-base.yaml` 中有：

```yaml
- conda-lock=2.5.7
```

所以实际传给 `conda create` 的包约束是：

```text
conda-lock=2.5.7
```

根据 `.conda-lock-env/conda-meta/history`，这一步实际执行的 Conda 命令是：

```bash
/home/tools/anaconda3/bin/conda create -y -p /home/brimon/chipyard/.conda-lock-env -c conda-forge conda-lock=2.5.7
```

当时使用的 Conda 版本是：

```text
conda version: 23.10.0
```

辅助环境创建时间记录为：

```text
2026-06-18 18:42:16 +0800
```

这个辅助环境不是最终使用的 Chipyard 开发环境。它存在的目的只是确保接下来的 lock 文件生成/安装使用固定版本的 `conda-lock=2.5.7`，减少由不同 `conda-lock` 版本造成的行为差异。

创建后，脚本通过：

```bash
source $(conda info --base)/etc/profile.d/conda.sh
conda activate /home/brimon/chipyard/.conda-lock-env
```

把当前 shell 切到 `.conda-lock-env`，后续执行 `conda-lock` 时使用这个辅助环境中的版本。

## 6. 判断是否需要重新生成 lock 文件

辅助环境激活后，脚本有两种情况会重新生成 lock 文件。

### 6.1 传入 `--use-unpinned-deps`

如果运行 `build-setup.sh` 时传入：

```bash
./build-setup.sh --use-unpinned-deps
```

或：

```bash
./build-setup.sh -ud
```

则 `USE_UNPINNED_DEPS=true`，脚本会直接运行：

```bash
$CYDIR/scripts/generate-conda-lockfiles.sh
```

当前脚本默认值是 `USE_UNPINNED_DEPS=false`，所以仅凭默认参数不会进入这一分支。

### 6.2 系统 glibc 与 `sysroot_linux-64` 不一致

无论是否使用 `--use-unpinned-deps`，脚本都会检查系统 glibc 版本：

```bash
SYS_GLIBC=$(ldd --version | awk '/ldd/{print $NF}')
DEFAULT_GLIBC=$(grep -i "sysroot_linux-64=" conda-reqs/chipyard-base.yaml | awk -F= '{print $2}')
```

当前系统命令输出为：

```text
ldd (GNU libc) 2.34
```

所以：

```text
SYS_GLIBC=2.34
```

如果 `SYS_GLIBC` 与 `DEFAULT_GLIBC` 不同，脚本会修改 `conda-reqs/chipyard-base.yaml` 中的 `sysroot_linux-64` 版本，并重新生成 lock 文件：

```bash
sed -i.bak "s/^\([[:space:]]*-\s*sysroot_linux-64=\).*/\1$SYS_GLIBC/" conda-reqs/chipyard-base.yaml
$CYDIR/scripts/generate-conda-lockfiles.sh
```

这次运行中确实出现了这个分支的痕迹：

- 当前 `conda-reqs/chipyard-base.yaml` 中是：

  ```yaml
  - sysroot_linux-64=2.34
  ```

- 备份文件 `conda-reqs/chipyard-base.yaml.bak` 中是：

  ```yaml
  - sysroot_linux-64=2.34 # need to be close to system glibc for VCS compatibility
  ```

这说明脚本运行了 `sed -i.bak`，把原行替换成了不带注释的版本。

这里有一个细节：脚本提取 `DEFAULT_GLIBC` 时使用的是：

```bash
awk -F= '{print $2}'
```

对于备份文件里的原始行：

```yaml
- sysroot_linux-64=2.34 # need to be close to system glibc for VCS compatibility
```

解析出来的 `DEFAULT_GLIBC` 不是纯 `2.34`，而是：

```text
2.34 # need to be close to system glibc for VCS compatibility
```

它与 `SYS_GLIBC=2.34` 字符串不相等，因此脚本会认为版本不一致并触发重写和 lock 文件重生成。也就是说，从数值版本看系统 glibc 和 sysroot 已经都是 2.34，但由于行内注释参与了字符串比较，这次仍然触发了重生成。

## 7. 重新生成 lock 文件的过程

`scripts/generate-conda-lockfiles.sh` 做了以下事情：

1. 打开 shell trace 和错误退出：

   ```bash
   set -ex
   ```

2. 定位 `conda-reqs` 目录。

3. 检查当前环境中的 `conda-lock` 版本是否与 `chipyard-base.yaml` 要求一致：

   ```bash
   conda-lock --version | grep $(grep "conda-lock" $REQS_DIR/chipyard-base.yaml | sed 's/^ \+-.*=//')
   ```

   当前要求是：

   ```text
   conda-lock=2.5.7
   ```

4. 对 `TOOLCHAIN_TYPE=riscv-tools` 生成完整 lock 文件：

   ```bash
   LOCKFILE=$REQS_DIR/conda-lock-reqs/conda-requirements-riscv-tools-linux-64.conda-lock.yml
   rm -rf $LOCKFILE

   conda-lock \
     --no-mamba \
     --no-micromamba \
     -f "$REQS_DIR/chipyard-base.yaml" \
     -f "$REQS_DIR/chipyard-extended.yaml" \
     -f "$REQS_DIR/docs.yaml" \
     -f "$REQS_DIR/riscv-tools.yaml" \
     -p linux-64 \
     --lockfile $LOCKFILE
   ```

   当前完整 lock 文件更新时间为：

   ```text
   2026-06-18 18:47:45 +0800
   ```

5. 再生成 lean lock 文件：

   ```bash
   LOCKFILE=$REQS_DIR/conda-lock-reqs/conda-requirements-riscv-tools-linux-64-lean.conda-lock.yml
   rm -rf $LOCKFILE

   conda-lock \
     --no-mamba \
     --no-micromamba \
     -f "$REQS_DIR/chipyard-base.yaml" \
     -f "$REQS_DIR/docs.yaml" \
     -f "$REQS_DIR/riscv-tools.yaml" \
     -p linux-64 \
     --lockfile $LOCKFILE
   ```

   当前 lean lock 文件更新时间为：

   ```text
   2026-06-18 18:48:51 +0800
   ```

这里的 `--no-mamba --no-micromamba` 明确要求 `conda-lock` 不使用 mamba/micromamba 求解，而走 Conda 路径。

## 8. 创建主环境 `.conda-env`

lock 文件准备好后，脚本决定主环境放在哪里。

默认没有传 `--conda-env-name NAME`，所以 `GLOBAL_ENV_NAME` 为空，执行这一分支：

```bash
CONDA_ENV_PATH=$CYDIR/.conda-env
CONDA_ENV_ARG="-p $CONDA_ENV_PATH"
CONDA_ENV_NAME=$CONDA_ENV_PATH
```

在当前运行中等价于：

```bash
CONDA_ENV_PATH=/home/brimon/chipyard/.conda-env
CONDA_ENV_ARG="-p /home/brimon/chipyard/.conda-env"
CONDA_ENV_NAME=/home/brimon/chipyard/.conda-env
```

随后执行：

```bash
conda-lock install --conda $CONDA_EXE $CONDA_ENV_ARG $LOCKFILE
```

在默认完整环境下，逻辑上等价于：

```bash
conda-lock install \
  --conda $CONDA_EXE \
  -p /home/brimon/chipyard/.conda-env \
  /home/brimon/chipyard/conda-reqs/conda-lock-reqs/conda-requirements-riscv-tools-linux-64.conda-lock.yml
```

`$CONDA_EXE` 是当前 Conda shell 集成提供的 Conda 可执行文件路径。在这次环境记录中，实际使用的是：

```text
/home/tools/anaconda3/bin/conda
```

从 `.conda-env/conda-meta/history` 看，`conda-lock install` 最终调用 Conda 创建主环境的命令是：

```bash
/home/tools/anaconda3/bin/conda create --file /tmp/tmp651hzipr --yes --prefix /home/brimon/chipyard/.conda-env
```

当时使用的 Conda 版本是：

```text
conda version: 23.10.0
```

主环境创建记录时间为：

```text
2026-06-18 18:50:03 +0800
```

`.conda-env` 目录状态时间为：

```text
2026-06-18 18:50:10 +0800
```

这说明主环境是通过 prefix 方式创建在仓库内部，而不是创建为全局命名环境。

如果运行时传了：

```bash
./build-setup.sh --conda-env-name NAME
```

脚本会改走命名环境分支：

```bash
CONDA_ENV_ARG="-n $GLOBAL_ENV_NAME"
CONDA_ENV_NAME=$GLOBAL_ENV_NAME
```

这时 `env.sh` 中也会写入：

```bash
conda activate NAME
```

而不是激活仓库内的 `.conda-env` 路径。

## 9. 主环境安装了哪些内容

当前完整 lock 文件和 `.conda-env/conda-meta` 显示，主环境安装了：

- 422 个 Conda 包。
- 16 个 pip 包。

主要来源 YAML 如下。

### 9.1 `chipyard-base.yaml`

基础依赖包括：

- 编译器与 sysroot：

  ```yaml
  - gcc=13.2
  - gxx=13.2
  - sysroot_linux-64=2.34
  - conda-gcc-specs
  - binutils
  ```

- 基础构建工具：

  ```yaml
  - autoconf
  - coreutils
  - jq
  - pip
  - make
  - git
  - ninja
  ```

- JVM / Scala / 仿真相关基础工具：

  ```yaml
  - sbt
  - openjdk=20
  - dtc
  - verilator==5.022
  ```

- lock 工具：

  ```yaml
  - conda-lock=2.5.7
  ```

实际主环境中可以看到这些关键包：

```text
gcc-13.2.0
gxx-13.2.0
sysroot_linux-64-2.34
verilator-5.022
openjdk-20.0.2
sbt-2.0.0
conda-lock-2.5.7
```

### 9.2 `chipyard-extended.yaml`

完整环境额外包含 FireSim、FireMarshal、VLSI/Hammer、Zephyr、CI/test 等依赖，例如：

- FireMarshal / 构建工具：`qemu`、`rsync`、`doit`、`gitpython`、`bison`、`flex`、`bc`、`patch`、`wget` 等。
- FireSim / AWS / 测试：`awscli`、`boto3`、`pytest`、`moto`、`mypy`、`s3fs==0.4.2` 等。
- VLSI/Hammer pip 包：

  ```yaml
  - pip:
      - hammer-vlsi[asap7]==1.2.0
  ```

- 其他 pip 包：

  ```yaml
  - pip:
      - sure
      - pylddwrap
  - pip:
      - fab-classic>=1.19.2
      - bcrypt<4.0.0
  ```

当前 `.conda-env` 中能看到这些 pip 包安装结果：

```text
hammer-vlsi 1.2.0
fab-classic 1.21.0
bcrypt 3.2.2
sure 2.0.1
pylddwrap 1.2.2
```

### 9.3 `docs.yaml`

文档构建依赖包括：

```yaml
- sphinx
- pygments
- sphinx-autobuild
- sphinx_rtd_theme
- docutils
```

### 9.4 `riscv-tools.yaml`

工具链包约束为：

```yaml
- riscv-tools==1.0.6
```

当前主环境中实际安装记录包含：

```text
ucb-bar/linux-64::riscv-tools-1.0.6-0_h1234567_g56c29e0
```

需要区分的是：Step 1 安装的是 Conda 包 `riscv-tools==1.0.6`。后续 Step 3 的 “Toolchain collateral” 会再构建 Spike、PK、tests、libgloss 等额外内容，默认安装到：

```text
$CONDA_PREFIX/riscv-tools
```

但这些后续构建不属于本文分析的 Conda 初始化 Step 1。

## 10. 激活主环境

主环境安装成功后，脚本执行：

```bash
source $(conda info --base)/etc/profile.d/conda.sh &&
conda activate $CONDA_ENV_NAME
```

当前等价于：

```bash
source $(conda info --base)/etc/profile.d/conda.sh
conda activate /home/brimon/chipyard/.conda-env
```

激活成功后，当前 shell 中会设置 Conda 相关变量，例如：

- `CONDA_PREFIX=/home/brimon/chipyard/.conda-env`
- `CONDA_DEFAULT_ENV=/home/brimon/chipyard/.conda-env` 或 Conda 对 prefix 环境生成的等价显示名
- `PATH` 前部加入 `.conda-env/bin`

如果这个激活失败，`exit_if_last_command_failed` 会让脚本以：

```text
Build script failed with exit code ... at step 1: Conda environment setup
```

的形式退出。

## 11. 写入 `env.sh` 的 Conda 激活块

主环境激活成功后，脚本构造一个可 source 的 Conda 激活前置代码：

```bash
if ! type conda >& /dev/null; then
    echo "::ERROR:: you must have conda in your environment first"
    return 1
fi

source $(conda info --base)/etc/profile.d/conda.sh
```

然后用 `replace_content` 写入 `env.sh` 的 `build-setup-conda` 块：

```bash
replace_content env.sh build-setup-conda "# line auto-generated by $0
$CONDA_ACTIVATE_PREAMBLE
conda activate $CONDA_ENV_NAME
source $CYDIR/scripts/fix-open-files.sh"
```

当前 `env.sh` 中实际写入的是：

```bash
# >>> build-setup-conda initialize >>>
# line auto-generated by ./build-setup.sh
if ! type conda >& /dev/null; then
    echo "::ERROR:: you must have conda in your environment first"
    return 1  # don't want to exit here because this file is sourced
fi

source $(conda info --base)/etc/profile.d/conda.sh
conda activate /home/brimon/chipyard/.conda-env
source /home/brimon/chipyard/scripts/fix-open-files.sh
# <<< build-setup-conda initialize <<<
```

`scripts/fix-open-files.sh` 会检查并调整当前 shell 的 open files soft limit：

```bash
HARD_LIMIT=$(ulimit -Hn)
SOFT_LIMIT=$(ulimit -Sn)
REQUIRED_LIMIT=16384
...
ulimit -Sn $(ulimit -Hn)
```

如果系统 hard limit 小于 16384，它会打印警告；无论如何都会尝试把 soft limit 提升到 hard limit。这个设置主要是为了规避后续 buildroot/FireMarshal 构建中的文件句柄限制问题。

`scripts/replace-content.py` 的写入方式是 marker 块替换：

```text
# >>> build-setup-conda initialize >>>
...
# <<< build-setup-conda initialize <<<
```

如果 `env.sh` 中已存在同 key 的块，就替换块内容；如果不存在，就追加到文件末尾。这使得重复生成 `env.sh` 时相对幂等，不会简单地无限追加同一段内容。

## 12. Step 1 完成后的检查

Step 1 的 `if run_step "1"; then ... fi` 结束后，脚本立即检查当前 shell 是否已经有 Conda 环境：

```bash
if [ -z ${CONDA_DEFAULT_ENV+x} ]; then
    echo "!!!!! WARNING: No conda environment detected. Did you activate the conda environment (e.x. 'conda activate base')?"
fi
```

正常执行 Step 1 时，前面已经 `conda activate /home/brimon/chipyard/.conda-env`，所以这里一般不会警告。

如果用户跳过 Step 1，但运行脚本前没有激活任何 Conda 环境，就会看到这个警告。

## 13. 本次运行留下的关键文件变化

这次 Conda 初始化相关的结果可以总结为：

1. 创建了辅助环境：

   ```text
   /home/brimon/chipyard/.conda-lock-env
   ```

   创建命令来自 `.conda-lock-env/conda-meta/history`：

   ```bash
   /home/tools/anaconda3/bin/conda create -y -p /home/brimon/chipyard/.conda-lock-env -c conda-forge conda-lock=2.5.7
   ```

2. 因 glibc/sysroot 字符串比较触发了 `chipyard-base.yaml` 修改：

   当前文件：

   ```yaml
   - sysroot_linux-64=2.34
   ```

   备份文件：

   ```yaml
   - sysroot_linux-64=2.34 # need to be close to system glibc for VCS compatibility
   ```

   这个变化说明 `sed -i.bak` 被执行过，并留下了：

   ```text
   conda-reqs/chipyard-base.yaml.bak
   ```

3. 重新生成了完整 lock 文件：

   ```text
   conda-reqs/conda-lock-reqs/conda-requirements-riscv-tools-linux-64.conda-lock.yml
   ```

   更新时间：

   ```text
   2026-06-18 18:47:45 +0800
   ```

4. 重新生成了 lean lock 文件：

   ```text
   conda-reqs/conda-lock-reqs/conda-requirements-riscv-tools-linux-64-lean.conda-lock.yml
   ```

   更新时间：

   ```text
   2026-06-18 18:48:51 +0800
   ```

5. 创建了主 Chipyard Conda 环境：

   ```text
   /home/brimon/chipyard/.conda-env
   ```

   `.conda-env/conda-meta/history` 中记录的实际创建命令为：

   ```bash
   /home/tools/anaconda3/bin/conda create --file /tmp/tmp651hzipr --yes --prefix /home/brimon/chipyard/.conda-env
   ```

6. 更新了 `env.sh`，使后续用户可以通过：

   ```bash
   source env.sh
   ```

   自动执行：

   ```bash
   conda activate /home/brimon/chipyard/.conda-env
   source /home/brimon/chipyard/scripts/fix-open-files.sh
   ```

## 14. Step 1 没有做的事情

为了避免混淆，下面这些不是 Conda 初始化 Step 1 的职责：

- 初始化 Git submodules：这是 Step 2。
- 构建 Spike、PK、riscv-tests、libgloss 等工具链 collateral：这是 Step 3。
- 运行 ctags：这是 Step 4。
- 预编译 Chipyard Scala 源码：这是 Step 5。
- 设置和预编译 FireSim：这是 Step 6/7。
- 设置和构建 FireMarshal 默认 workload：这是 Step 8/9。
- 下载或构建 CIRCT：这是 Step 10。
- 仓库清理：这是 Step 11。

Step 1 只负责 Conda 环境本身，包括辅助 `.conda-lock-env`、主 `.conda-env`、lock 文件必要更新，以及 `env.sh` 中的激活逻辑。

## 15. 复用或重建环境时的注意事项

当前本地已经存在：

```text
.conda-lock-env
.conda-env
env.sh
```

因此，继续使用当前环境时应先：

```bash
source env.sh
```

如果需要继续执行 `build-setup.sh` 的后续步骤，应跳过 Step 1：

```bash
./build-setup.sh -s 1
```

如果确实要重建 Conda 环境，则需要先删除：

```text
.conda-lock-env
.conda-env
```

然后重新运行不跳过 Conda 的 `build-setup.sh`。

另外，这次因为 `sysroot_linux-64` 原行带注释而触发了 lock 文件重生成。若后续希望避免这种由注释引发的重复重生成，可以考虑让脚本解析 `DEFAULT_GLIBC` 时去掉空白和注释，例如只提取版本号部分。但这属于脚本行为改进，不是本次文档分析范围。
