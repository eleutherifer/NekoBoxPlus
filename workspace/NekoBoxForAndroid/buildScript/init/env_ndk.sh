#!/bin/bash

if [ -z "$ANDROID_HOME" ]; then
  if [ -d "$HOME/Android/Sdk" ]; then
    export ANDROID_HOME="$HOME/Android/Sdk"
  elif [ -d "$HOME/.local/lib/android/sdk" ]; then
    export ANDROID_HOME="$HOME/.local/lib/android/sdk"
  elif [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
  fi
fi

_NDK=""

if [ -n "$ANDROID_NDK_HOME" ] && [ -f "$ANDROID_NDK_HOME/source.properties" ]; then
  _NDK="$ANDROID_NDK_HOME"
elif [ -n "$NDK" ] && [ -f "$NDK/source.properties" ]; then
  _NDK="$NDK"
elif [ -f "$ANDROID_HOME/ndk/27.3.13750724/source.properties" ]; then
  _NDK="$ANDROID_HOME/ndk/27.3.13750724"
elif [ -d "$ANDROID_HOME/ndk" ]; then
  _LATEST_NDK=$(find "$ANDROID_HOME/ndk" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort -V | tail -n 1)
  if [ -n "$_LATEST_NDK" ] && [ -f "$ANDROID_HOME/ndk/$_LATEST_NDK/source.properties" ]; then
    _NDK="$ANDROID_HOME/ndk/$_LATEST_NDK"
  fi
fi

[ -f "$_NDK/source.properties" ] || _NDK="$ANDROID_HOME/ndk-bundle"

if [ ! -f "$_NDK/source.properties" ]; then
  echo "Error: NDK not found."
  exit 1
fi

export ANDROID_NDK_HOME=$_NDK
export NDK=$_NDK
