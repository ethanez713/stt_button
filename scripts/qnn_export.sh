#!/bin/bash
# Export S1-mini to a QNN .pte for SM8750 with the patched ExecuTorch in ~/tools, one export stage
# per process (calibrate, decode, prefill, compile; see export_stage() in the patch). Each stage's
# output lands in the artifact directory, so a rerun resumes at the first stage without a
# stage_<stage>.done marker. RSS and swap are sampled every 2 s. Run it detached
# (setsid nohup … &): the export's peak makes Claude Code reap its own background shells.
#
#   [STAGES="calibrate decode"] scripts/qnn_export.sh <name> <llama.py args...>
#   → ~/tools/s1-mini-qnn/<name>/ (artifacts), <name>.<stage>.log, <name>.<stage>.mem.csv
name=$1; shift
# The export is the first thing the kernel should kill if memory runs out; children inherit this.
echo 1000 > /proc/self/oom_score_adj
out=~/tools/s1-mini-qnn/$name
mkdir -p $out
# QAIRT's envsetup.sh reads "$@", so the export's arguments are set aside before sourcing it.
args=("$@"); set --
cd ~/tools/executorch && source ~/tools/qairt/venv310/bin/activate && source ~/tools/qairt/qairt/2.37.0.250724/bin/envsetup.sh >/dev/null
export PYTHONPATH=~/tools:$PYTHONPATH LD_LIBRARY_PATH=~/tools/libcxx-14:~/tools/qairt/qairt/2.37.0.250724/lib/x86_64-linux-clang:$LD_LIBRARY_PATH

for stage in ${STAGES:-calibrate decode prefill compile}; do
  [ -e $out/stage_$stage.done ] && { echo "$stage: already done"; continue; }
  ET_QNN_STAGE=$stage python examples/qualcomm/oss_scripts/llama/llama.py --build_folder build-android --soc_model SM8750 --decoder_model s1-mini --temperature 0 --prompt "unused at compile" --compile_only --artifact $out "${args[@]}" > $out.$stage.log 2>&1 &
  pid=$!
  echo "t,rss_mb,swap_mb" > $out.$stage.mem.csv
  start=$(date +%s)
  while kill -0 $pid 2>/dev/null; do
    awk -v t=$(( $(date +%s) - start )) '/VmRSS/{r=$2} /VmSwap/{s=$2} END{printf "%d,%d,%d\n", t, r/1024, s/1024}' /proc/$pid/status >> $out.$stage.mem.csv 2>/dev/null
    sleep 2
  done
  wait $pid; rc=$?
  peak=$(awk -F, 'NR>1 && $2+$3>m{m=$2+$3; r=$2; s=$3} END{print m " MB (rss " r ", swap " s ")"}' $out.$stage.mem.csv)
  echo "$stage: exit $rc after $(( $(date +%s) - start )) s, peak $peak"
  [ $rc -eq 0 ] || exit $rc
  [ $stage = compile ] && touch $out/stage_compile.done
done
echo DONE
