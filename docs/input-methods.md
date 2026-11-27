# Input methods (VM settings › Controls)

Two optional input methods, both off by default, set per VM (and as defaults in App settings).

## Mouse and keyboard
* The guest gets an emulated **touchpad** (`AEmulator Touchpad`) and **keyboard** (`AEmulator Keyboard`); its
  touch screen (`/dev/input/event0`) is removed. The guest screen is the touchpad: tap = click, two fingers = scroll
  (the guest's own gesture code, Android 3.2+; older builds show no pointer).
* A **keyboard** button appears in the navigation bar. It opens a floating, half-transparent keyboard (opacity slider,
  drag by the title bar). Shift/Ctrl/Alt latch for the next key. While this mode is on, *every* key (navigation bar,
  volume, power, a connected hardware keyboard) is sent through the keyboard device.
* Applies at the next full VM boot. Works on every engine and firmware architecture: the qemu engines (ARM and x86-32
  guests, nodes from `libaemushim`) and the native arm64 engine (nodes from the runner `libaemu64_<api>`, sockets
  `AEMU64_PAD` / `AEMU64_KBD`). Not available in recovery or framework-less boots (the toggle is ignored there).
* Implementation: `native/guestshim/vinput.h` (same technique as `trackball.h`: an empty node in `/dev/input`, `open()`
  returns a socket to the app, `EVIOC*` answered in the shim), `core/InputService.kt` (servers `dev/aemu_touchpad`,
  `dev/aemu_keyboard`), `core/VirtualInput.kt` (protocol, layout, latch). **`libaemushim.so` must be rebuilt**
  (`native/guestshim/build.sh`) and copied to `app/src/main/assets/engines/common/`.

## Keypad
* A **keypad** button in the navigation bar opens a floating window: soft keys (Menu/Back), arrows + OK, Call/End,
  0–9 * #, like a feature phone. The keys go through the ordinary key device, so **touch stays on** and the method can
  be combined with mouse and keyboard. Works on every engine.
* Digits, Call, End, * and # are added to the emulator's key layout (`dhd-touch.kl`, `TreeFixer.keyLayout`).

## Xperia Play slider and gamepad (R800 models only)
* The toggle **Input: Xperia Play slider and gamepad** is shown in VM settings only when the image's `ro.product.model` is an R800
  model (`R800i`, `R800a`, `R800at`, `R800x` ...; `VirtualInputPolicy.isXperiaPlayModel`). Needs Android 2.3+, a qemu
  engine (ARM or x86-32 guest) and a full VM restart. Not on the native arm64 engine (its runners have no slider node), not in
  recovery or framework-less boots.
* The guest gets a switch-only evdev device, **`AEmulator Slider`** (`EV_SW`, `SW_LID`), from `native/guestshim/vinput.h`. A phone
  with a slider reports it as its lid: `SW_LID` 0 = open (slider out), 1 = shut. The VM always boots shut (EVIOCGSW says so);
  `InputService` sends the real state as the first event of every connection. In the stock firmware's framework-res an open
  slider bypasses the keyguard (`config_bypass_keyguard_if_slider_open`) and makes the D-pad reachable
  (`config_lidNavigationAccessibility` = 1); closing it does not sleep the device.
* An **Xperia Play** button in the navigation bar slides the keypad out/in. While it is out a floating **gamepad** shows: D-pad,
  Cross (DPAD_CENTER), Circle (BACK), Square (BUTTON_X), Triangle (BUTTON_Y), L / R (BUTTON_L1 / R1), Select, Start, Menu, Play
  (BUTTON_MODE). Its keys go through the ordinary key device, so **touch stays on**; the extra scan codes (307, 308, 310, 311,
  314, 315, 316) are added to `dhd-touch.kl` (`TreeFixer.keyLayout`, only for an R800 image). Closing the window slides it shut.
* Not emulated: the two analog touchpads. The firmware's input stack (Sony's `libui`) reads the gamepad through its own joystick
  path (`/dev/input/js*`, layout `gamepad.gl`), which the shim does not provide. The keycodes above are the documented Xperia
  Play ones from memory, not read from the ROM's `gamepad.gl`: compare them with it if a game reads a button wrongly.
* **`libaemushim.so` must be rebuilt** for both qemu shims (`native/guestshim/build-zig.sh`, `build-x86.sh`) and copied to
  `app/src/main/assets/engines/common/` and `common-x86/`. Host test: `native/guestshim/tests/vinput-slider-ioctl.c`.

