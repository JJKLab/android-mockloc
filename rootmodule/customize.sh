#!/system/bin/sh
# Runs at flash time, inside the installer. `ui_print` / `abort` come from the Magisk/KSU
# update-binary that CI injects at META-INF/com/google/android/update-binary.

ui_print "- mockloc: config lives in /data/adb/mockloc/config.prop"
ui_print "- mockloc: write it with 'mockloc.py set --persist', or by hand"

mkdir -p /data/adb/mockloc
chmod 700 /data/adb/mockloc
if [ ! -f /data/adb/mockloc/config.prop ]; then
  cat > /data/adb/mockloc/config.prop <<'EOF'
MOCKLOC_ENABLED=false
MOCKLOC_GLOBAL=true
MOCKLOC_PROVIDERS=gps,network,fused
MOCKLOC_LAT=0
MOCKLOC_LNG=0
MOCKLOC_ACC=5.0
MOCKLOC_RADIUS=0
MOCKLOC_INTERVAL=15
MOCKLOC_TARGETS=
MOCKLOC_LABEL=
EOF
  chmod 600 /data/adb/mockloc/config.prop
fi
