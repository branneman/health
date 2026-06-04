# java
JBR_HOME="$HOME/Applications/Android Studio.app/Contents/jbr/Contents/Home"
if [ -d "$JBR_HOME" ]; then
  export JAVA_HOME="$JBR_HOME"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

# adb
if [ -d "$HOME/Library/Android/sdk/platform-tools" ]; then
  export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
fi

# emulator
if [ -d "$HOME/Library/Android/sdk/emulator" ]; then
  export PATH="$HOME/Library/Android/sdk/emulator:$PATH"
fi
