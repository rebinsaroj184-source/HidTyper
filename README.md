# HID Typer

Phone ko Bluetooth HID keyboard bana ke ek range ke numbers (Enter ke saath) doosre device par type karta hai. Root nahi chahiye — Android ka official `BluetoothHidDevice` API use karta hai.

## Build karne ke steps (PC pe Android Studio chahiye)

1. Android Studio kholo → **Open** → is `HidTyper` folder ko select karo.
2. Gradle sync hone do (pehli baar internet chahiye, dependencies download hongi).
3. **Build → Build Bundle(s)/APK(s) → Build APK(s)**.
4. APK phone me install karo (`app/build/outputs/apk/debug/app-debug.apk`).

## App use karne ke steps

1. Target device (vivo V25) ke Bluetooth settings me jao, apne phone ko **paired** karo (system Bluetooth se, app ke bahar).
2. App kholo → **"1. Register as HID Keyboard"** dabao → Bluetooth permission allow karo.
3. **"2. Show Paired Devices"** dabao — pehla paired device se connect hoga (agar multiple devices paired hain to code me `paired.first()` ko specific device se replace karo).
4. Target device pe confirm karo ki keyboard connect ho gaya (jaise normal BT keyboard).
5. Start/End number aur delay (ms) daalo → **"3. Start Typing"**.

## Notes / Limitations

- `minSdk 28` — Android 9+ required (BluetoothHidDevice API yahi se available hai).
- Delay har number ke baad hai; agar target device slow react kare to delay badhao (200-300ms).
- Agar sirf ek hi paired device ho to sab automatic chal jayega; multiple ho to `pickPairedDevice()` function me device-selection dialog add karna padega.
- Code standalone hai — MacroDroid ya kisi third-party keyboard app ki zaroorat nahi.
