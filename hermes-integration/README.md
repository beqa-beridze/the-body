# Hermes integration

Copy these into the Hermes home to give the agent the phone tools:
  cp -r plugins/android-body ~/.hermes/plugins/
  cp -r skills/android-body  ~/.hermes/skills/
Enable the plugin in ~/.hermes/config.yaml:
  plugins:
    enabled: [android-body]
The plugin reads the bridge token from $ANDROID_BRIDGE_TOKEN or ~/.config/body/bridge_token
(shown/rotated in the Body app). 18 android_* tools. check_fn gates on the bridge /health.
