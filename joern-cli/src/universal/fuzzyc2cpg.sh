#!/usr/bin/env sh

if [ "$(uname -s)" = "Darwin" ]; then
    SCRIPT_ABS_PATH=$(greadlink -f "$0")
else
    SCRIPT_ABS_PATH=$(readlink -f "$0")
fi
SCRIPT_ABS_DIR=$(dirname "$SCRIPT_ABS_PATH")
SCRIPT="$SCRIPT_ABS_DIR"/frontends/fuzzyc2cpg/bin/fuzzyc2cpg

if [ ! -f "$SCRIPT" ]; then
    echo "Unable to find $SCRIPT, have you created the distribution?"
    exit 1
fi

$SCRIPT -J-XX:+UseG1GC -J-XX:CompressedClassSpaceSize=128m "$@"
