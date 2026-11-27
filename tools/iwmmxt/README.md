# iWMMXt -> NEON (Marvell firmware)

`app/src/main/java/app/aemu/core/Iwmmxt.kt` rewrites Wireless MMX code (Marvell Skia blitters) to ARMv7 NEON before boot;
`TreeFixer.iwmmxtToNeon()` calls it and keeps the firmware's file in `system/.aemu-parked/*.iwmmxt`.

`IwmmxtTables.kt` is generated from GNU as (no hand-written bit layouts):

    python3 tools/iwmmxt/gen_tables.py app/src/main/java/app/aemu/core/IwmmxtTables.kt

Prelinked libraries (`<load address>` + `PRE ` at the very end of the file) keep those 8 bytes last: the 2.x linker reads them from the end, and a library that loses them is relocated a second time.

`patch_elf.py IN.so OUT.so` is the Python reference of the same translator (needs binutils-arm-linux-gnueabi); its output is
byte-identical to the Kotlin one.

The code cave is a PT_LOAD with flags RWX, not RX: the 2.x linker mprotects the span from the first to the last non-writable segment to R+X after relocation, and an RX cave after the data made .data/.bss read-only (SIGSEGV in static constructors of libskia / libcamera).

## Beyond the Python reference (2026-11-26)

The Kotlin translator now covers more than patch_elf.py: register-count shifts, signed unpacks, wmulw[us]m, wavg2, wsad, tmia*, wacc, wabs,
wabsdiff, tmovmsk, textrc, wpackd*, the iWMMXt2 wldrd/wstrd [Rn, +-Rm] and wCGR wldrw/wstrw forms and 8-bit flag producers. Their line
shapes come from the Kotlin side:

    kotlinc Iwmmxt.kt IwmmxtTables.kt harness/Shapes.kt harness/Main.kt -include-runtime -d iwt.jar
    java -jar iwt.jar --shapes extra.txt
    IWMMXT_EXTRA_SHAPES=extra.txt python3 gen_tables.py ../../app/src/main/java/app/aemu/core/IwmmxtTables.kt

Verification (needs qemu-user and binutils-arm-linux-gnueabi; IWT_JAR=iwt.jar):

- verify/bulk.py runs each instruction as iWMMXt on qemu-arm -cpu pxa270 and as translated NEON on -cpu cortex-a8 and compares all registers.
  qemu own iWMMXt has bugs (wsadb/wsadh with a < b, wpack*, tmovmskh, wmac* sign extension, shifts by more than the lane size, tmia 32-bit
  product); verify/model.py checks those against the architectural definition instead.
- verify/enccheck.py compares the built-in assembler with GNU as for every line shape.

Not translated (no reference to check the semantics against): wavg4/wavg4r, the iWMMXt2 wmia*, wmiaw*, wqmia*, wqmul*.
