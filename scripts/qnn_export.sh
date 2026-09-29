#!/bin/bash
# Export S1-mini to a QNN .pte for SM8750 with the patched ExecuTorch in ~/tools, sampling the
# export's RSS and swap every 2 s. Run it detached (setsid nohup … &): the export's peak makes
# Claude Code reap its own background shells.
#
#   scripts/qnn_export.sh <name> <llama.py args...>
#   → ~/tools/s1-mini-qnn/<name>/ (artifacts), <name>.log, <name>.mem.csv; summary on stdout
name=$1; shift
# The export is the first thing the kernel should kill if memory runs out; children inherit this.
echo 1000 > /proc/self/oom_score_adj
out=~/tools/s1-mini-qnn/$name
mkdir -p ~/tools/s1-mini-qnn
# QAIRT's envsetup.sh reads "$@", so the export's arguments are set aside before sourcing it.
args=("$@"); set --
cd ~/tools/executorch && source ~/tools/qairt/venv310/bin/activate && source ~/tools/qairt/qairt/2.37.0.250724/bin/envsetup.sh >/dev/null
export PYTHONPATH=~/tools:$PYTHONPATH LD_LIBRARY_PATH=~/tools/libcxx-14:~/tools/qairt/qairt/2.37.0.250724/lib/x86_64-linux-clang:$LD_LIBRARY_PATH
python examples/qualcomm/oss_scripts/llama/llama.py --build_folder build-android --soc_model SM8750 --decoder_model s1-mini --temperature 0 --prompt "unused at compile" --compile_only --artifact $out "${args[@]}" > $out.log 2>&1 &
pid=$!
echo "t,rss_mb,swap_mb" > $out.mem.csv
start=$(date +%s)
while kill -0 $pid 2>/dev/null; do
  awk -v t=$(( $(date +%s) - start )) '/VmRSS/{r=$2} /VmSwap/{s=$2} END{printf "%d,%d,%d\n", t, r/1024, s/1024}' /proc/$pid/status >> $out.mem.csv 2>/dev/null
  sleep 2
done
wait $pid; rc=$?
echo "EXIT $rc start=$(date -d @$start +%T)"
awk -F, 'NR>1 && $2+$3>m{m=$2+$3; r=$2; s=$3; t=$1} END{print "peak rss+swap_mb=" m " (rss " r ", swap " s ") at t=" t "s"}' $out.mem.csv
