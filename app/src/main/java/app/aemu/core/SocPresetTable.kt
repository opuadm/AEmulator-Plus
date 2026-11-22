/* Added for AEmulator Plus, 2026-11-19. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

/*
 * THE PRESET TABLE. Nothing but data: every chip the "Preset" menu offers is one row here, and nothing else needs to
 * change to add, remove or correct one. SocProfile.kt turns a row into /proc/cpuinfo, /sys/devices/soc0, the properties
 * and (with the phone's GPU on) the GL_VENDOR / GL_RENDERER the guest reads.
 *
 * ARM rows are made with qc(...) / chip(...), x86 rows with atom(...) / desktop(...). Column order is written out in
 * each helper's signature at the bottom of the file.
 *
 * Where the numbers come from
 *  - soc_id: the kernel's drivers/soc/qcom/socinfo.c table. A chip that table does not list (or that predates it) has "" and
 *    the guest gets no soc_id node, rather than a made-up one.
 *  - cores, clocks, GPU, process node: the vendor's product pages / briefs.
 *  - ro.board.platform: the board platform name devices with that chip report. "" = the firmware's own value stays.
 *  - Snapdragon 4s Gen 2 and 7s Gen 4: Qualcomm does not name the GPU; the Adreno numbers are the ones the phone vendors' spec
 *    sheets give (611, 810).
 *  - revision / serial_number are not specifications (every phone has its own); they are fixed, plausible values.
 *  - Snapdragon 732G is SM7150-AC (not SM7250-AC, which is the 768G).
 *
 * Added 2026-11-20 (second pass)
 *  - Checked against real phones' /proc/cpuinfo, soc0 and build.prop (github.com/pytorch/cpuinfo test/mock dumps of a Pixel 2 XL,
 *    Galaxy S7 / S8 / S9, Nexus 5X / 6, Moto G, Redmi Note 3 / 4, Oppo A37 / R9 / R15, Meizu Pro 6 / 7+ ...). Corrected by them:
 *    MSM8998 big cores are part 0x800 (variant 0xa, little 0x801 rev 4); MSM8996's little cores are 0x211 and its big ones 0x205 (they were
 *    swapped); SDM845 is variant 7 rev 12 / variant 6 rev 13; MSM8992 revisions are 3 (A53) and 2 (A57); MSM8916 has no crypto extensions
 *    (Features "fp asimd evtstrm crc32"); SM7150 (730 / 732G) is "sm6150" on a Pixel 4a, not "sdmmagpie".
 *  - SM8735's platform was "sun" (the 8 Elite's); no source gives its own name, so it is "" (the firmware's own stays).
 *  - Platform names: SM4450 / SM6450 / SM6475 "parrot", SM7550 "crow", SM8635 "cliffs", SM7635 / SM6650 "volcano", SM8850 "canoe" come from kernel
 *    patches, OnePlus kernel build configs and phones' own reports; SM7675 "pineapple" is the OnePlus kernel build's name for it (least certain).
 *    Rows with "" platform or "" soc_id are chips for which no source was found; they report nothing rather than a guess.
 *  - Values that are not in any source (Kryo variant / revision of chips without a real dump, kgsl clock steps) follow the verified chips
 *    of the same core generation; the kgsl steps keep the shape of the Adreno 630's. GPU top clocks and foundries come from the Wikipedia list.

 * Added 2026-11-20 (third pass: MediaTek)
 *  - The chip NAMES come from en.wikipedia.org/wiki/List_of_MediaTek_systems_on_chips and nothing else does. Every other column comes from
 *    other places:
 *      cores / clocks / GPU / node: MediaTek's own product pages and launch material, checked this pass for Helio G85 (mediatek.com), Helio X30
 *      (mediatek.jp page: A73 2.6 GHz; the launch press release said 2.5), Helio X20 (TechInfoDepot), Helio P23 / P30 (AnandTech, GSMArena,
 *      Android Authority: 2.3 / 1.65 GHz, Mali-G71 MP2 770 / 950 MHz, 16 nm), Helio P20 / P10 (Android Authority, Fudzilla), Dimensity 9400
 *      (3.62 / 3.3 / 2.4 GHz), Dimensity 8400 (A725 x8, 3.25 / 3.0 / 2.1 GHz, Mali-G720 MC7), Dimensity 9300 / 9300+ and 9200 (Android Authority,
 *      91mobiles). The remaining Dimensity / Helio G rows are MediaTek's launch figures from memory and were NOT re-checked against a page
 *      this pass (Dimensity 8300's middle cluster is the one conflict: most sources say 3.2 GHz, one says 3.0).
 *      MT6582 / MT6592 / MT6735 / MT6761 / MT6765 clocks are likewise unchecked.
 *    Left out on purpose: Dimensity 9500 / 7500 (Arm C1 cores: the MIDR part numbers are not in the kernel's cputype.h yet), Dimensity 9600 and the
 *    other 2026 launches (no non-Wikipedia source yet), Genio / Pentonic / automotive / Wi-Fi / modem / feature-phone parts (not phone SoCs).
 *  - CPU part numbers: Linux arch/arm64/include/asm/cputype.h (A720 0xd81, X4 0xd82, X925 0xd85, A725 0xd87, A520 0xd80, A715 0xd4d, X3 0xd4e).
 *  - Features / revision: real dumps (pytorch/cpuinfo test/mock): Helio P10 (Oppo R9, Alcatel Revvl: A53 rev 2, crypto extensions present,
 *    Hardware "MT6755"), Helio X30 (Meizu Pro 7 Plus: A73 part 0xd09 rev 2), Helio P60 (Oppo R15: A73 rev 2, crypto present). The A53-only
 *    entry chips (MT6735, A22, P35) follow the existing MT6739 / MT6762 rows (no crypto extensions): unverified. Revisions and variants of every
 *    other core follow the verified chips of the same core generation, as in the Qualcomm rows.
 *  - ro.board.platform is "mt" + the model number, which is what MediaTek device trees use (TARGET_BOARD_PLATFORM := mt6765 ...). Exception: Helio
 *    G85 (MT6769Z) is "mt6768" (the Redmi 9 tree is named mt6768, /e/OS lists the Redmi Note 9 as "MT6768 Helio G85"); the least certain name here.
 *  - Fourth pass: Helio G35 / G36 / G37 (2.3 / 2.2 / 2.3 GHz + 1.8 GHz, PowerVR GE8320 680 MHz, 12 nm), G70 / G80 / G88 (2x A75 2.0 GHz + 6x A55 1.7 /
 *    1.8 / 1.8 GHz, Mali-G52 MC2) and P25 (2.6 / 1.6 GHz, Mali-T880 MP2) checked against mediatek.com pages and Notebookcheck / 91mobiles. The
 *    Dimensity 6020 / 6080 / 7020 / 7050 rows are renames of earlier rows (same silicon). Dimensity 1050, 1100, 8000 and Helio G100 are from memory.
 *    Dimensity 1100's platform name "mt6891" is the "mt" + model rule, not seen in a device tree.
 *  - soc_id is the model digits, as in the existing MediaTek rows (MediaTek has no socinfo table). Serials come from fakeSerial(id).
 *  - GL strings: Mali / Immortalis rows use ARM's names. Helio X30 (PowerVR 7XT GT7400 Plus) and Dimensity 930 / 7020 (IMG BXM-8-256) are the GPU
 *    names as given by the maintainer, written in the "PowerVR ..." form the existing GE8xxx rows use; the exact driver strings are unverified.
 */
object SocPresetTable {
    private const val QTI = "QTI"

    // ---- ARM -------------------------------------------------------------------------------------------------------
    // the order here is the order of the menu: oldest first
    private fun armRows(): List<SocSpec> = listOf(
        //  id          label                                    model       platform    soc_id arch  clusters (count, impl, variant, part, rev, maxMHz, minMHz)                                      gpu                        node
        qc("qsd8250", "Snapdragon S1 QSD8250",          "QSD8250", "qsd8k",   "",    7, SCORPION,   listOf(c(1, 0x51, 1, 0x00f, 2, 1000, 245)),                                  "Adreno (TM) 200", "65 nm"),
        qc("msm8255", "Snapdragon S2 MSM8255",          "MSM8255", "msm7x30", "",    7, SCORPION,   listOf(c(1, 0x51, 1, 0x00f, 2, 1000, 245)),                                  "Adreno (TM) 205", "45 nm"),
        qc("msm8260", "Snapdragon S3 MSM8260",          "MSM8260", "msm8660", "70",    7, SCORPION,   listOf(c(2, 0x51, 1, 0x00f, 2, 1200, 384)),                                  "Adreno (TM) 220", "45 nm"),
        qc("msm8225", "Snapdragon S4 Play MSM8225",     "MSM8225", "msm7x27a","127",    7, A5_V7,      listOf(c(2, 0x41, 0, 0xc05, 1, 1200, 245)),                                  "Adreno (TM) 203", "45 nm"),
        qc("msm8225q", "Snapdragon 200 MSM8225Q",     "MSM8225Q", "msm7x27a","168",    7, A5_V7,      listOf(c(4, 0x41, 0, 0xc05, 1, 1400, 245)),                                  "Adreno (TM) 203", "45 nm"),
        // (the three below were presets before the table moved to this file)
        qc("msm8960", "Snapdragon S4 Plus MSM8960",     "MSM8960", "msm8960", "87",  7, KRAIT,      listOf(c(2, 0x51, 1, 0x04d, 0, 1512, 384)),                                  "Adreno (TM) 225", "28 nm", hw = "QCT MSM8960 MTP", rev = "1.0", serial = "3156203849", bogo = "13.53"),
        qc("msm8960ab", "Snapdragon S4 Pro MSM8960AB",     "MSM8960AB", "msm8960", "138",  7, KRAIT,      listOf(c(2, 0x51, 1, 0x06f, 0, 1700, 384)),                                  "Adreno (TM) 320", "28 nm"),
        qc("msm8210", "Snapdragon 200 MSM8210",         "MSM8210", "msm8610", "162", 7, A7_V7,      listOf(c(2, 0x41, 0, 0xc07, 5, 1200, 300)),                                  "Adreno (TM) 302", "28 nm", hw = "Qualcomm MSM 8210 (Flattened Device Tree)"),
        qc("msm8909", "Snapdragon 210 MSM8909",         "MSM8909", "msm8909", "245",    7, A7_V7,      listOf(c(4, 0x41, 0, 0xc07, 5, 1100, 200)),                                  "Adreno (TM) 304", "28 nm"),
        qc("msm8909w", "Snapdragon Wear 2100 MSM8909W",         "MSM8909W", "msm8909", "300",    7, A7_V7,      listOf(c(4, 0x41, 0, 0xc07, 5, 1200, 200)),                                  "Adreno (TM) 304", "28 nm"),
        qc("msm8226", "Snapdragon 400 MSM8226",         "MSM8226", "msm8226", "158", 7, A7_V7,      listOf(c(4, 0x41, 0, 0xc07, 5, 1200, 300)),                                  "Adreno (TM) 305", "28 nm", hw = "Qualcomm MSM 8226 (Flattened Device Tree)"),
        qc("msm8974", "Snapdragon 800 MSM8974",         "MSM8974", "msm8974", "126", 7, KRAIT,      listOf(c(4, 0x51, 2, 0x06f, 0, 2265, 300)),                                  "Adreno (TM) 330", "28 nm", hw = "Qualcomm MSM 8974 (Flattened Device Tree)", rev = "2.1", serial = "2917450318", bogo = "38.40"),
        qc("msm8974pro_aa", "Snapdragon 801 MSM8974PRO-AA",         "MSM8974PRO-AA", "msm8974", "217", 7, KRAIT,      listOf(c(4, 0x51, 2, 0x06f, 0, 2265, 300)),                                  "Adreno (TM) 330", "28 nm"),
        qc("msm8974pro_ab", "Snapdragon 801 MSM8974PRO-AB",         "MSM8974PRO-AB", "msm8974", "218", 7, KRAIT,      listOf(c(4, 0x51, 2, 0x06f, 0, 2360, 300)),                                  "Adreno (TM) 330", "28 nm"),
        qc("msm8974pro_ac", "Snapdragon 801 MSM8974PRO-AC",         "MSM8974PRO-AC", "msm8974", "194", 7, KRAIT,      listOf(c(4, 0x51, 2, 0x06f, 0, 2450, 300)),                                  "Adreno (TM) 330", "28 nm"),
        qc("msm8916", "Snapdragon 410 MSM8916",         "MSM8916", "msm8916", "206", 8, A64_LITE,   listOf(c(4, 0x41, 0, 0xd03, 0, 1200, 200)),                                  "Adreno (TM) 306", "28 nm"),
        qc("msm8917", "Snapdragon 425 MSM8917",         "MSM8917", "msm8937", "303",    8, A64_BASE,   listOf(c(4, 0x41, 0, 0xd03, 4, 1400, 960)),                                  "Adreno (TM) 308", "28 nm"),
        qc("msm8940", "Snapdragon 435 MSM8940",         "MSM8940", "msm8937", "313",    8, A64_BASE,   listOf(c(8, 0x41, 0, 0xd03, 4, 1400, 960)),                                  "Adreno (TM) 505", "28 nm"),
        qc("sdm439",  "Snapdragon 439 SDM439",          "SDM439",  "msm8937", "353",    8, A64_BASE,   listOf(c(4, 0x41, 0, 0xd03, 4, 1450, 960), c(4, 0x41, 0, 0xd03, 4, 1950, 960)), "Adreno (TM) 505", "12 nm"),
        qc("sdm450",  "Snapdragon 450 SDM450",          "SDM450",  "msm8953", "338", 8, A64_BASE,   listOf(c(8, 0x41, 0, 0xd03, 4, 1800, 652)),                                  "Adreno (TM) 506", "14 nm"),
        qc("msm8952", "Snapdragon 617 MSM8952",         "MSM8952", "msm8952", "264",    8, A64_BASE,   listOf(c(4, 0x41, 0, 0xd03, 4, 1200, 400), c(4, 0x41, 0, 0xd03, 4, 1500, 400)), "Adreno (TM) 405", "28 nm"),
        qc("msm8953", "Snapdragon 625 MSM8953",         "MSM8953", "msm8953", "293", 8, A64_BASE,   listOf(c(8, 0x41, 0, 0xd03, 4, 2000, 652)),                                  "Adreno (TM) 506", "14 nm"),
        qc("sdm636",  "Snapdragon 636 SDM636",          "SDM636",  "sdm660",  "345", 8, A64_BASE,   listOf(c(4, 0x51, 0, 0x801, 0, 1600, 633), c(4, 0x51, 0, 0x800, 0, 1800, 633)),  "Adreno (TM) 509", "14 nm"),
        qc("sm6115",  "Snapdragon 662 SM6115",          "SM6115",  "bengal",  "444",    8, A64_V82,    listOf(c(4, 0x51, 0, 0x801, 0, 1800, 300), c(4, 0x51, 0, 0x800, 0, 2000, 300)),  "Adreno (TM) 610", "11 nm"),
        qc("sm4250",  "Snapdragon 460 SM4250-AA",          "SM4250-AA",  "bengal",  "417",    8, A64_V82,    listOf(c(4, 0x51, 0, 0x801, 0, 1800, 300), c(4, 0x51, 0, 0x800, 0, 1600, 300)),  "Adreno (TM) 610", "11 nm"),
        qc("sm6225",  "Snapdragon 680 SM6225",          "SM6225",  "bengal",  "518",    8, A64_V82,    listOf(c(4, 0x51, 0, 0x801, 0, 1900, 300), c(4, 0x51, 0, 0x800, 0, 2400, 300)),  "Adreno (TM) 610", "6 nm"),
        qc("sm6375",  "Snapdragon 695 SM6375",          "SM6375",  "holi",    "507", 8, A64_V82,    listOf(c(6, 0x41, 0, 0xd05, 0, 1700, 300), c(2, 0x41, 0, 0xd41, 0, 2200, 691)),  "Adreno (TM) 619", "6 nm"),
        qc("sm4350_480",  "Snapdragon 480 SM4350",      "SM4350","holi",   "454",    8, A64_V82,    listOf(c(6, 0x51, 0, 0x805, 0, 1800, 300), c(2, 0x51, 0, 0x804, 0, 2000, 710)),  "Adreno (TM) 619", "8 nm"),
        qc("sm4350",  "Snapdragon 480+ SM4350-AC",      "SM4350-AC","holi",   "472",    8, A64_V82,    listOf(c(6, 0x51, 0, 0x805, 0, 1900, 300), c(2, 0x51, 0, 0x804, 0, 2200, 710)),  "Adreno (TM) 619", "8 nm"),
        qc("sm4635",  "Snapdragon 4s Gen 2 SM4635",     "SM4635",  "pitti",        "623",    8, A64_V82,    listOf(c(6, 0x41, 0, 0xd05, 0, 1800, 300), c(2, 0x41, 0, 0xd41, 0, 2000, 691)),  "Adreno (TM) 611", "4 nm"),
        qc("sm6475",  "Snapdragon 6 Gen 3 SM6475-AB",   "SM6475-AB","parrot", "663", 8, A64_V82,    listOf(c(4, 0x41, 0, 0xd05, 0, 1800, 300), c(4, 0x41, 0, 0xd41, 0, 2400, 691)),  "Adreno (TM) 710", "4 nm"),
        qc("sm7125",  "Snapdragon 720G SM7125",         "SM7125",  "atoll",   "443",    8, A64_V82,    listOf(c(6, 0x51, 0, 0x805, 0, 1800, 300), c(2, 0x51, 0, 0x804, 0, 2300, 710)),  "Adreno (TM) 618", "8 nm"),
        qc("sm7150",  "Snapdragon 732G SM7150-AC",      "SM7150-AC","sm6150","365",  8, A64_V82,    listOf(c(6, 0x51, 0, 0x805, 0, 1800, 300), c(2, 0x51, 0, 0x804, 0, 2300, 710)),  "Adreno (TM) 618", "8 nm"),
        qc("sm7150p",  "Snapdragon 732G SM7150P",      "SM7150P","sm6150","366",  8, A64_V82,    listOf(c(6, 0x51, 0, 0x805, 0, 1800, 300), c(2, 0x51, 0, 0x804, 0, 2300, 710)),  "Adreno (TM) 618", "8 nm"),
        qc("sm7325",  "Snapdragon 778G SM7325",         "SM7325",  "yupik",   "475",    8, A64_V82,    listOf(c(4, 0x41, 0, 0xd05, 0, 1900, 300), c(3, 0x41, 0, 0xd41, 0, 2200, 691), c(1, 0x41, 0, 0xd41, 0, 2400, 806)), "Adreno (TM) 642L", "6 nm"),
        qc("sm7325p",  "Snapdragon 778G Plus SM7325-AE",         "SM7325-AE",  "yupik",   "499",    8, A64_V82,    listOf(c(4, 0x41, 0, 0xd05, 0, 1900, 300), c(3, 0x41, 0, 0xd41, 0, 2200, 691), c(1, 0x41, 0, 0xd41, 0, 2500, 806)), "Adreno (TM) 642L", "6 nm"),
        qc("sm7475",  "Snapdragon 7+ Gen 2 SM7475-AB",  "SM7475-AB","ukee",   "591", 8, A64_V9,     listOf(c(4, 0x41, 0, 0xd46, 0, 1800, 300), c(3, 0x41, 0, 0xd47, 0, 2490, 633), c(1, 0x41, 1, 0xd48, 0, 2910, 806)), "Adreno (TM) 725", "4 nm"),
        qc("sm7635_gen3",  "Snapdragon 7s Gen 3 SM7635",  "SM7635","volcano",       "636",    8, A64_V9,     listOf(c(4, 0x41, 0, 0xd80, 0, 1800, 300), c(3, 0x41, 0, 0xd81, 0, 2400, 633), c(1, 0x41, 0, 0xd81, 0, 2500, 806)), "Adreno (TM) 810", "4 nm"),
        qc("sm7635",  "Snapdragon 7s Gen 4 SM7635-AC",  "SM7635-AC","volcano",       "712",    8, A64_V9,     listOf(c(4, 0x41, 0, 0xd80, 0, 1800, 300), c(3, 0x41, 0, 0xd81, 0, 2400, 633), c(1, 0x41, 0, 0xd81, 0, 2700, 806)), "Adreno (TM) 810", "4 nm"),
        qc("msm8992", "Snapdragon 808 MSM8992",         "MSM8992", "msm8992", "251", 8, A64_BASE,   listOf(c(4, 0x41, 0, 0xd03, 3, 1440, 384), c(2, 0x41, 1, 0xd07, 2, 1824, 384)),  "Adreno (TM) 418", "20 nm"),
        qc("msm8994", "Snapdragon 810 MSM8994",         "MSM8994", "msm8994", "207", 8, A64_BASE,   listOf(c(4, 0x41, 0, 0xd03, 2, 1555, 384), c(4, 0x41, 1, 0xd07, 1, 1958, 384)),  "Adreno (TM) 430", "20 nm"),
        qc("msm8996", "Snapdragon 820 MSM8996",         "MSM8996", "msm8996", "246", 8, A64_BASE,   listOf(c(2, 0x51, 1, 0x211, 2, 1593, 307), c(2, 0x51, 1, 0x205, 2, 2150, 307)),  "Adreno (TM) 530", "14 nm"),
        qc("msm8996l", "Snapdragon 820 Lite MSM8996L",         "MSM8996L", "msm8996", "302", 8, A64_BASE,   listOf(c(2, 0x51, 1, 0x211, 2, 1363, 307), c(2, 0x51, 1, 0x205, 2, 1800, 307)),  "Adreno (TM) 530", "14 nm"),
        qc("msm8996au", "Snapdragon 820Am MSM8996AU",         "MSM8996AU", "msm8996", "310", 8, A64_BASE,   listOf(c(2, 0x51, 1, 0x211, 2, 1593, 307), c(2, 0x51, 1, 0x205, 2, 2150, 307)),  "Adreno (TM) 530", "14 nm"),
        qc("msm8998", "Snapdragon 835 MSM8998",         "MSM8998", "msm8998", "292", 8, A64_BASE,   listOf(c(4, 0x51, 10, 0x801, 4, 1900, 300), c(4, 0x51, 10, 0x800, 1, 2450, 300)),  "Adreno (TM) 540", "10 nm"),
        qc("sdm845",  "Snapdragon 845 SDM845",          "SDM845",  "sdm845",  "321", 8, A64_V82,    listOf(c(4, 0x51, 7, 0x803, 12, 1766, 300), c(4, 0x51, 6, 0x802, 13, 2803, 825)),  "Adreno (TM) 630", "10 nm"),
        qc("sm8150",  "Snapdragon 855 SM8150",          "SM8150",  "msmnile", "339", 8, A64_V82,    listOf(c(4, 0x51, 0, 0x805, 14, 1785, 300), c(3, 0x51, 0, 0x804, 14, 2419, 710), c(1, 0x51, 0, 0x804, 14, 2841, 825)), "Adreno (TM) 640", "7 nm", hw = "Qualcomm Technologies, Inc SM8150", rev = "2.1", serial = "1749263058", bogo = "38.40"),
        qc("sm8250",  "Snapdragon 865 SM8250",          "SM8250",  "kona",    "356", 8, A64_V82,    listOf(c(4, 0x41, 0, 0xd05, 0, 1800, 300), c(3, 0x41, 0, 0xd0d, 0, 2420, 710), c(1, 0x41, 0, 0xd0d, 0, 2840, 844)), "Adreno (TM) 650", "7 nm"),
        qc("sm8350",  "Snapdragon 888 SM8350",          "SM8350",  "lahaina", "439", 8, A64_V82,    listOf(c(4, 0x41, 0, 0xd05, 0, 1800, 300), c(3, 0x41, 0, 0xd41, 0, 2420, 710), c(1, 0x41, 1, 0xd44, 0, 2840, 844)), "Adreno (TM) 660", "5 nm"),
        qc("sm8450",  "Snapdragon 8 Gen 1 SM8450",      "SM8450",  "taro",    "457", 8, A64_V9,     listOf(c(4, 0x41, 0, 0xd46, 0, 1800, 300), c(3, 0x41, 0, 0xd47, 0, 2500, 633), c(1, 0x41, 1, 0xd48, 0, 3000, 806)), "Adreno (TM) 730", "4 nm"),
        qc("sm8475",  "Snapdragon 8+ Gen 1 SM8475",     "SM8475",  "cape",    "530", 8, A64_V9,     listOf(c(4, 0x41, 0, 0xd46, 0, 2016, 300), c(3, 0x41, 0, 0xd47, 0, 2800, 633), c(1, 0x41, 1, 0xd48, 0, 3200, 806)), "Adreno (TM) 730", "4 nm", hw = "Qualcomm Technologies, Inc SM8475", rev = "1.1", serial = "2480961735", bogo = "38.40"),
        qc("sm8475p",  "Snapdragon 8+ Gen 1 SM8475P",     "SM8475P",  "cape",    "531", 8, A64_V9,     listOf(c(4, 0x41, 0, 0xd46, 0, 2016, 300), c(3, 0x41, 0, 0xd47, 0, 2800, 633), c(1, 0x41, 1, 0xd48, 0, 3200, 806)), "Adreno (TM) 730", "4 nm", hw = "Qualcomm Technologies, Inc SM8475", rev = "1.1", bogo = "38.40"),
        qc("sm8550",  "Snapdragon 8 Gen 2 SM8550",      "SM8550",  "kalama",  "519", 8, A64_V9,     listOf(c(3, 0x41, 0, 0xd46, 0, 2000, 300), c(2, 0x41, 0, 0xd47, 0, 2800, 633), c(2, 0x41, 0, 0xd4d, 0, 2800, 633), c(1, 0x41, 1, 0xd4e, 0, 3200, 806)), "Adreno (TM) 740", "4 nm"),
        qc("sm8550p",  "Snapdragon 8 Gen 2 for Galaxy SM8550-AC",      "SM8550-AC",  "kalama",  "536", 8, A64_V9,     listOf(c(3, 0x41, 0, 0xd46, 0, 2000, 300), c(2, 0x41, 0, 0xd47, 0, 2800, 633), c(2, 0x41, 0, 0xd4d, 0, 2800, 633), c(1, 0x41, 1, 0xd4e, 0, 3360, 806)), "Adreno (TM) 740", "4 nm"),
        qc("sm8650",  "Snapdragon 8 Gen 3 SM8650",      "SM8650",  "pineapple","557",8, A64_V9,     listOf(c(2, 0x41, 0, 0xd80, 0, 2270, 300), c(2, 0x41, 0, 0xd81, 0, 2960, 633), c(3, 0x41, 0, 0xd81, 0, 3150, 633), c(1, 0x41, 0, 0xd82, 0, 3300, 806)), "Adreno (TM) 750", "4 nm"),
        qc("sm8650p",  "Snapdragon 8 Gen 3 for Galaxy SM8650-AC",      "SM8650-AC",  "pineapple","577",8, A64_V9,     listOf(c(2, 0x41, 0, 0xd80, 0, 2270, 300), c(2, 0x41, 0, 0xd81, 0, 2960, 633), c(3, 0x41, 0, 0xd81, 0, 3150, 633), c(1, 0x41, 0, 0xd82, 0, 3400, 806)), "Adreno (TM) 750", "4 nm"),
        qc("sm8735",  "Snapdragon 8s Gen 4 SM8735",     "SM8735",  "",        "681", 8, A64_V9,     listOf(c(2, 0x41, 0, 0xd81, 0, 2020, 300), c(2, 0x41, 0, 0xd81, 0, 2800, 633), c(3, 0x41, 0, 0xd81, 0, 3000, 633), c(1, 0x41, 0, 0xd82, 0, 3210, 806)), "Adreno (TM) 825", "4 nm"),
        qc("sm8750",  "Snapdragon 8 Elite SM8750",      "SM8750",  "sun",     "618", 8, A64_ORYON,  listOf(c(6, 0x51, 1, 0x001, 0, 3530, 384), c(2, 0x51, 1, 0x001, 0, 4320, 384)),         "Adreno (TM) 830", "3 nm", hw = "Qualcomm Technologies, Inc SM8750", rev = "2.0", serial = "3714052861", bogo = "38.40"),
        // ---- added 2026-11-20: soc_id from the Linux dt-bindings/arm/qcom,ids.h ("" where that file lacks the chip), cores and clocks from the
        //      Wikipedia list, ro.board.platform from AOSP / vendor device trees (see the notes at the top). "" platform = the firmware keeps its own.
        qc("msm8660", "Snapdragon S3 MSM8660", "MSM8660", "msm8660", "71", 7, SCORPION, listOf(c(2, 81, 1, 0x00f, 2, 1500, 384)), "Adreno (TM) 220", "45 nm"),
        qc("msm8625", "Snapdragon S4 Play MSM8625", "MSM8625", "msm7x27a", "129", 7, A5_V7, listOf(c(2, 65, 0, 0xc05, 1, 1200, 245)), "Adreno (TM) 203", "45 nm"),
        qc("msm8625q", "Snapdragon 200 MSM8625Q", "MSM8625Q", "msm7x27a", "169", 7, A5_V7, listOf(c(4, 65, 0, 0xc05, 1, 1400, 245)), "Adreno (TM) 203", "45 nm"),
        qc("msm8610", "Snapdragon 200 MSM8610", "MSM8610", "msm8610", "147", 7, A7_V7, listOf(c(2, 65, 0, 0xc07, 5, 1200, 300)), "Adreno (TM) 302", "28 nm", hw = "Qualcomm MSM 8610 (Flattened Device Tree)"),
        qc("msm8212", "Snapdragon 200 MSM8212", "MSM8212", "msm8610", "164", 7, A7_V7, listOf(c(4, 65, 0, 0xc07, 5, 1200, 300)), "Adreno (TM) 302", "28 nm", hw = "Qualcomm MSM 8212 (Flattened Device Tree)"),
        qc("msm8612", "Snapdragon 200 MSM8612", "MSM8612", "msm8610", "165", 7, A7_V7, listOf(c(4, 65, 0, 0xc07, 5, 1200, 300)), "Adreno (TM) 302", "28 nm", hw = "Qualcomm MSM 8612 (Flattened Device Tree)"),
        qc("msm8208", "Snapdragon 208 MSM8208", "MSM8208", "msm8909", "259", 7, A7_V7, listOf(c(2, 65, 0, 0xc07, 5, 1100, 200)), "Adreno (TM) 304", "28 nm"),
        qc("msm8905", "Qualcomm 205 MSM8905", "MSM8905", "msm8909", "331", 7, A7_V7, listOf(c(2, 65, 0, 0xc07, 5, 1100, 200)), "Adreno (TM) 304", "28 nm"),
        qc("apq8026", "Snapdragon 400 APQ8026", "APQ8026", "msm8226", "199", 7, A7_V7, listOf(c(4, 65, 0, 0xc07, 5, 1200, 300)), "Adreno (TM) 305", "28 nm", hw = "Qualcomm APQ 8026 (Flattened Device Tree)"),
        qc("msm8926", "Snapdragon 400 MSM8926", "MSM8926", "msm8226", "200", 7, A7_V7, listOf(c(4, 65, 0, 0xc07, 5, 1200, 300)), "Adreno (TM) 305", "28 nm", hw = "Qualcomm MSM 8926 (Flattened Device Tree)"),
        qc("msm8626", "Snapdragon 400 MSM8626", "MSM8626", "msm8226", "145", 7, A7_V7, listOf(c(4, 65, 0, 0xc07, 5, 1200, 300)), "Adreno (TM) 305", "28 nm", hw = "Qualcomm MSM 8626 (Flattened Device Tree)"),
        qc("apq8028", "Snapdragon 400 APQ8028", "APQ8028", "msm8226", "219", 7, A7_V7, listOf(c(4, 65, 0, 0xc07, 5, 1600, 300)), "Adreno (TM) 305", "28 nm", hw = "Qualcomm APQ 8028 (Flattened Device Tree)"),
        qc("msm8228", "Snapdragon 400 MSM8228", "MSM8228", "msm8226", "221", 7, A7_V7, listOf(c(4, 65, 0, 0xc07, 5, 1600, 300)), "Adreno (TM) 305", "28 nm", hw = "Qualcomm MSM 8228 (Flattened Device Tree)"),
        qc("msm8628", "Snapdragon 400 MSM8628", "MSM8628", "msm8226", "223", 7, A7_V7, listOf(c(4, 65, 0, 0xc07, 5, 1600, 300)), "Adreno (TM) 305", "28 nm", hw = "Qualcomm MSM 8628 (Flattened Device Tree)"),
        qc("msm8928", "Snapdragon 400 MSM8928", "MSM8928", "msm8226", "224", 7, A7_V7, listOf(c(4, 65, 0, 0xc07, 5, 1600, 300)), "Adreno (TM) 305", "28 nm", hw = "Qualcomm MSM 8928 (Flattened Device Tree)"),
        qc("msm8930", "Snapdragon S4 Plus MSM8930", "MSM8930", "msm8960", "116", 7, KRAIT, listOf(c(2, 81, 1, 0x04d, 0, 1200, 384)), "Adreno (TM) 305", "28 nm", hw = "QCT MSM8930 MTP"),
        qc("msm8930aa", "Snapdragon 400 MSM8930AA", "MSM8930AA", "msm8960", "142", 7, KRAIT, listOf(c(2, 81, 1, 0x06f, 2, 1400, 384)), "Adreno (TM) 305", "28 nm", hw = "QCT MSM8930 MTP"),
        qc("msm8930ab", "Snapdragon 400 MSM8930AB", "MSM8930AB", "msm8960", "154", 7, KRAIT, listOf(c(2, 81, 1, 0x06f, 2, 1700, 384)), "Adreno (TM) 305", "28 nm", hw = "QCT MSM8930 MTP"),
        qc("apq8064", "Snapdragon 600 APQ8064", "APQ8064", "msm8960", "109", 7, KRAIT, listOf(c(4, 81, 1, 0x06f, 2, 1500, 384)), "Adreno (TM) 320", "28 nm", hw = "QCT APQ8064 MTP", bogo = "13.53"),
        qc("apq8064ab", "Snapdragon 600 APQ8064AB", "APQ8064AB", "msm8960", "153", 7, KRAIT, listOf(c(4, 81, 1, 0x06f, 2, 1900, 384)), "Adreno (TM) 320", "28 nm", hw = "QCT APQ8064 MTP", bogo = "13.53"),
        qc("apq8084", "Snapdragon 805 APQ8084", "APQ8084", "apq8084", "178", 7, KRAIT, listOf(c(4, 81, 3, 0x06f, 1, 2700, 300)), "Adreno (TM) 420", "28 nm", hw = "Qualcomm APQ 8084 (Flattened Device Tree)", rev = "1.1"),
        qc("qm215", "Qualcomm 215 QM215", "QM215", "qm215", "386", 8, A64_BASE, listOf(c(4, 65, 0, 0xd03, 4, 1300, 960)), "Adreno (TM) 308", "28 nm"),
        qc("apq8016", "Snapdragon 410 APQ8016", "APQ8016", "msm8916", "247", 8, A64_LITE, listOf(c(4, 65, 0, 0xd03, 0, 1200, 200)), "Adreno (TM) 306", "28 nm"),
        qc("msm8936", "Snapdragon 610 MSM8936", "MSM8936", "msm8916", "233", 8, A64_BASE, listOf(c(4, 65, 0, 0xd03, 4, 1700, 200)), "Adreno (TM) 405", "28 nm"),
        qc("msm8939", "Snapdragon 615 MSM8939", "MSM8939", "msm8916", "239", 8, A64_BASE, listOf(c(4, 65, 0, 0xd03, 4, 1100, 200), c(4, 65, 0, 0xd03, 4, 1700, 200)), "Adreno (TM) 405", "28 nm"),
        qc("msm8929", "Snapdragon 415 MSM8929", "MSM8929", "msm8916", "268", 8, A64_BASE, listOf(c(4, 65, 0, 0xd03, 4, 1000, 200), c(4, 65, 0, 0xd03, 4, 1400, 200)), "Adreno (TM) 405", "28 nm"),
        qc("msm8920", "Snapdragon 427 MSM8920", "MSM8920", "msm8937", "320", 8, A64_BASE, listOf(c(4, 65, 0, 0xd03, 4, 1400, 960)), "Adreno (TM) 308", "28 nm"),
        qc("msm8937", "Snapdragon 430 MSM8937", "MSM8937", "msm8937", "294", 8, A64_BASE, listOf(c(4, 65, 0, 0xd03, 4, 1100, 960), c(4, 65, 0, 0xd03, 4, 1400, 960)), "Adreno (TM) 505", "28 nm"),
        qc("sdm429", "Snapdragon 429 SDM429", "SDM429", "msm8937", "354", 8, A64_BASE, listOf(c(4, 65, 0, 0xd03, 4, 2000, 960)), "Adreno (TM) 504", "12 nm"),
        qc("msm8956", "Snapdragon 650 MSM8956", "MSM8956", "msm8952", "266", 8, A64_BASE, listOf(c(4, 65, 0, 0xd03, 4, 1400, 400), c(2, 65, 0, 0xd08, 2, 1800, 400)), "Adreno (TM) 510", "28 nm"),
        qc("msm8976", "Snapdragon 652 MSM8976", "MSM8976", "", "278", 8, A64_BASE, listOf(c(4, 65, 0, 0xd03, 4, 1400, 400), c(4, 65, 0, 0xd08, 2, 1800, 400)), "Adreno (TM) 510", "28 nm"),
        qc("sdm630", "Snapdragon 630 SDM630", "SDM630", "sdm660", "318", 8, A64_BASE, listOf(c(4, 65, 0, 0xd03, 4, 1800, 633), c(4, 65, 0, 0xd03, 4, 2200, 633)), "Adreno (TM) 508", "14 nm"),
        qc("sdm632", "Snapdragon 632 SDM632", "SDM632", "msm8953", "349", 8, A64_BASE, listOf(c(4, 81, 10, 0x801, 4, 1800, 652), c(4, 81, 10, 0x800, 1, 1800, 652)), "Adreno (TM) 506", "14 nm"),
        qc("sdm660", "Snapdragon 660 SDM660", "SDM660", "sdm660", "317", 8, A64_BASE, listOf(c(4, 81, 10, 0x801, 4, 1840, 633), c(4, 81, 10, 0x800, 1, 2200, 633)), "Adreno (TM) 512", "14 nm"),
        qc("sm6125", "Snapdragon 665 SM6125", "SM6125", "trinket", "394", 8, A64_V82, listOf(c(4, 81, 10, 0x801, 4, 1800, 300), c(4, 81, 10, 0x800, 1, 2000, 300)), "Adreno (TM) 610", "11 nm"),
        qc("sdm670", "Snapdragon 670 SDM670", "SDM670", "sdm710", "336", 8, A64_V82, listOf(c(6, 81, 7, 0x803, 12, 1700, 300), c(2, 81, 6, 0x802, 13, 2000, 825)), "Adreno (TM) 615", "10 nm"),
        qc("sdm710", "Snapdragon 710 SDM710", "SDM710", "sdm710", "", 8, A64_V82, listOf(c(6, 81, 7, 0x803, 12, 1700, 300), c(2, 81, 6, 0x802, 13, 2200, 825)), "Adreno (TM) 616", "10 nm"),
        qc("sdm712", "Snapdragon 712 SDM712", "SDM712", "sdm710", "", 8, A64_V82, listOf(c(6, 81, 7, 0x803, 12, 1700, 300), c(2, 81, 6, 0x802, 13, 2300, 825)), "Adreno (TM) 616", "10 nm"),
        qc("sm6150", "Snapdragon 675 SM6150", "SM6150", "sm6150", "", 8, A64_V82, listOf(c(6, 81, 0, 0x805, 0, 1700, 300), c(2, 81, 0, 0x804, 0, 2000, 710)), "Adreno (TM) 612", "11 nm"),
        qc("sdm850", "Snapdragon 850 SDM850", "SDM850", "sdm845", "348", 8, A64_V82, listOf(c(4, 81, 7, 0x803, 12, 1770, 300), c(4, 81, 6, 0x802, 13, 2960, 825)), "Adreno (TM) 630", "10 nm"),
        qc("sm7250", "Snapdragon 765G SM7250-AB", "SM7250-AB", "lito", "400", 8, A64_V82, listOf(c(6, 65, 0, 0xd05, 0, 1800, 300), c(1, 65, 0, 0xd0b, 0, 2200, 710), c(1, 65, 0, 0xd0b, 0, 2400, 806)), "Adreno (TM) 620", "7 nm"),
        qc("sm6350", "Snapdragon 690 SM6350", "SM6350", "", "434", 8, A64_V82, listOf(c(6, 65, 0, 0xd05, 0, 1700, 300), c(2, 65, 0, 0xd0d, 0, 2000, 710)), "Adreno (TM) 619L", "8 nm"),
        qc("sm7225", "Snapdragon 750G SM7225", "SM7225", "", "459", 8, A64_V82, listOf(c(6, 65, 0, 0xd05, 0, 1800, 300), c(2, 65, 0, 0xd0d, 0, 2200, 710)), "Adreno (TM) 619", "8 nm"),
        qc("sm4375", "Snapdragon 4 Gen 1 SM4375", "SM4375", "", "", 8, A64_V82, listOf(c(6, 65, 0, 0xd05, 0, 1800, 300), c(2, 65, 0, 0xd41, 0, 2000, 691)), "Adreno (TM) 619", "6 nm"),
        qc("sm7450", "Snapdragon 7 Gen 1 SM7450-AB", "SM7450-AB", "", "506", 8, A64_V9, listOf(c(4, 65, 0, 0xd46, 0, 1800, 300), c(3, 65, 0, 0xd47, 0, 2360, 633), c(1, 65, 0, 0xd47, 0, 2400, 806)), "Adreno (TM) 644", "4 nm"),
        qc("sm4450", "Snapdragon 4 Gen 2 SM4450", "SM4450", "parrot", "568", 8, A64_V82, listOf(c(6, 65, 0, 0xd05, 0, 1950, 300), c(2, 65, 0, 0xd41, 0, 2200, 691)), "Adreno (TM) 613", "4 nm"),
        qc("sm6450", "Snapdragon 6 Gen 1 SM6450", "SM6450", "parrot", "537", 8, A64_V82, listOf(c(4, 65, 0, 0xd05, 0, 1800, 300), c(4, 65, 0, 0xd41, 0, 2200, 691)), "Adreno (TM) 710", "4 nm"),
        qc("sm7550", "Snapdragon 7 Gen 3 SM7550-AB", "SM7550-AB", "crow", "", 8, A64_V9, listOf(c(4, 65, 0, 0xd46, 0, 1800, 300), c(3, 65, 0, 0xd4d, 0, 2400, 633), c(1, 65, 0, 0xd4d, 0, 2630, 806)), "Adreno (TM) 720", "4 nm"),
        qc("sm7675", "Snapdragon 7+ Gen 3 SM7675-AB", "SM7675-AB", "pineapple", "", 8, A64_V9, listOf(c(3, 65, 0, 0xd80, 0, 1900, 300), c(4, 65, 0, 0xd81, 0, 2600, 633), c(1, 65, 0, 0xd82, 0, 2800, 806)), "Adreno (TM) 732", "4 nm"),
        qc("sm8635", "Snapdragon 8s Gen 3 SM8635", "SM8635", "cliffs", "", 8, A64_V9, listOf(c(3, 65, 0, 0xd80, 0, 2000, 300), c(4, 65, 0, 0xd81, 0, 2800, 633), c(1, 65, 0, 0xd82, 0, 3000, 806)), "Adreno (TM) 735", "4 nm"),
        qc("sm6650", "Snapdragon 6 Gen 4 SM6650", "SM6650", "volcano", "640", 8, A64_V9, listOf(c(4, 65, 0, 0xd80, 0, 1800, 300), c(3, 65, 0, 0xd81, 0, 2200, 633), c(1, 65, 0, 0xd81, 0, 2300, 806)), "Adreno (TM) 810", "4 nm"),
        qc("sm7750", "Snapdragon 7 Gen 4 SM7750-AB", "SM7750-AB", "", "659", 8, A64_V9, listOf(c(3, 65, 0, 0xd80, 0, 1840, 300), c(4, 65, 0, 0xd81, 0, 2400, 633), c(1, 65, 0, 0xd81, 0, 2800, 806)), "Adreno (TM) 722", "4 nm"),
        qc("qcm6490", "Qualcomm QCM6490", "QCM6490", "yupik", "497", 8, A64_V82, listOf(c(4, 65, 0, 0xd05, 0, 1900, 300), c(3, 65, 0, 0xd41, 0, 2400, 691), c(1, 65, 0, 0xd41, 0, 2700, 806)), "Adreno (TM) 643", "6 nm"),
        qc("sm8850", "Snapdragon 8 Elite Gen 5 SM8850", "SM8850", "canoe", "660", 8, A64_ORYON, listOf(c(6, 81, 1, 0x001, 0, 3630, 384), c(2, 81, 1, 0x001, 0, 4610, 384)), "Adreno (TM) 840", "3 nm", hw = "Qualcomm Technologies, Inc SM8850"),
        qc("sm8950", "Snapdragon 8 Elite Gen 6 SM8950", "SM8950", "", "735", 8, A64_ORYON, listOf(c(3, 81, 1, 0x001, 0, 3740, 384), c(3, 81, 1, 0x001, 0, 4030, 384), c(2, 81, 1, 0x001, 0, 5010, 384)), "Adreno (TM) 845", "2 nm"),
        qc("sm8975", "Snapdragon 8 Elite Extreme Gen 6 SM8975", "SM8975", "", "707", 8, A64_ORYON, listOf(c(3, 81, 1, 0x001, 0, 3740, 384), c(3, 81, 1, 0x001, 0, 4030, 384), c(2, 81, 1, 0x001, 0, 5010, 384)), "Adreno (TM) 850", "2 nm"),
        // (the MediaTek chips of the earlier menu: kept so a VM that chose one keeps it)
        mt("mt6580", "MediaTek MT6580",                 "MT6580", "mt6580", "6580", 7, V7,       listOf(c(4, 0x41, 0, 0xc07, 5, 1300, 598)),                                  "Mali-400 MP",     "ARM",    "28 nm", serial = "1385207349", bogo = "26.00"),
        mt("mt6739", "MediaTek MT6739",                 "MT6739", "mt6739", "6739", 8, A64_LITE, listOf(c(4, 0x41, 0, 0xd03, 4, 1500, 598)),                                  "PowerVR Rogue GE8100",  "Imagination Technologies", "28 nm", serial = "2036184097", bogo = "26.00"),
        mt("mt6762", "MediaTek Helio P22 MT6762",       "MT6762", "mt6762", "6762", 8, A64_LITE, listOf(c(8, 0x41, 0, 0xd03, 4, 2000, 598)),                                  "PowerVR Rogue GE8320",  "Imagination Technologies", "12 nm", serial = "1853390726", bogo = "26.00"),
        mt("mt6789", "MediaTek Helio G99 MT6789",       "MT6789", "mt6789", "6789", 8, A64_V82,  listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd0b, 0, 2200, 500)), "Mali-G57 MC2", "ARM",   "6 nm",  serial = "3490172865", bogo = "26.00"),
        mt("mt6878", "MediaTek Dimensity 7300 MT6878",  "MT6878", "mt6878", "6878", 8, A64_V82,  listOf(c(4, 0x41, 0, 0xd05, 0, 2000, 500), c(4, 0x41, 1, 0xd41, 0, 2500, 500)), "Mali-G615 MC2", "ARM",  "4 nm",  serial = "1126973504", bogo = "26.00"),
        // ---- added 2026-11-20 (third pass): more MediaTek chips. Chip NAMES come from the Wikipedia list; cores, clocks, GPU and node from
        //      other sources (see the third-pass notes at the top). "" GPU = no source found for the GL strings: the phone's own are reported.
        //  id            label                                   model        platform  soc_id arch features   clusters (count, impl, variant, part, rev, maxMHz, minMHz)                                                                      GL_RENDERER            vendor       node
        mt("mt6582", "MediaTek MT6582", "MT6582", "mt6582", "6582", 7, V7, listOf(c(4, 0x41, 0, 0xc07, 3, 1300, 598)), "Mali-400 MP", ARM_GPU, "28 nm"),
        mt("mt6592", "MediaTek MT6592", "MT6592", "mt6592", "6592", 7, V7, listOf(c(8, 0x41, 0, 0xc07, 3, 1700, 598)), "Mali-450 MP", ARM_GPU, "28 nm"),
        mt("mt6735", "MediaTek MT6735", "MT6735", "mt6735", "6735", 8, A64_LITE, listOf(c(4, 0x41, 0, 0xd03, 2, 1300, 598)), "Mali-T720", ARM_GPU, "28 nm"),
        mt("mt6755", "MediaTek Helio P10 MT6755", "MT6755", "mt6755", "6755", 8, A64_BASE, listOf(c(4, 0x41, 0, 0xd03, 2, 1000, 598), c(4, 0x41, 0, 0xd03, 2, 2000, 598)), "Mali-T860", ARM_GPU, "28 nm"),
        mt("mt6797", "MediaTek Helio X20 MT6797", "MT6797", "mt6797", "6797", 8, A64_BASE, listOf(c(4, 0x41, 0, 0xd03, 2, 1400, 598), c(4, 0x41, 0, 0xd03, 2, 1850, 598), c(2, 0x41, 0, 0xd08, 2, 2100, 598)), "Mali-T880", ARM_GPU, "20 nm"),
        mt("mt6757", "MediaTek Helio P20 MT6757", "MT6757", "mt6757", "6757", 8, A64_BASE, listOf(c(4, 0x41, 0, 0xd03, 4, 1600, 500), c(4, 0x41, 0, 0xd03, 4, 2300, 500)), "Mali-T880", ARM_GPU, "16 nm"),
        mt("mt6763", "MediaTek Helio P23 MT6763", "MT6763", "mt6763", "6763", 8, A64_BASE, listOf(c(4, 0x41, 0, 0xd03, 4, 1650, 500), c(4, 0x41, 0, 0xd03, 4, 2300, 500)), "Mali-G71 MP2", ARM_GPU, "16 nm"),
        mt("mt6758", "MediaTek Helio P30 MT6758", "MT6758", "mt6758", "6758", 8, A64_BASE, listOf(c(4, 0x41, 0, 0xd03, 4, 1650, 500), c(4, 0x41, 0, 0xd03, 4, 2300, 500)), "Mali-G71 MP2", ARM_GPU, "16 nm"),
        mt("mt6799", "MediaTek Helio X30 MT6799", "MT6799", "mt6799", "6799", 8, A64_BASE, listOf(c(4, 0x41, 0, 0xd04, 0, 1900, 500), c(4, 0x41, 0, 0xd03, 4, 2200, 500), c(2, 0x41, 0, 0xd09, 2, 2600, 500)), "PowerVR Rogue GT7400 Plus", IMG, "10 nm"),
        mt("mt6761", "MediaTek Helio A22 MT6761", "MT6761", "mt6761", "6761", 8, A64_LITE, listOf(c(4, 0x41, 0, 0xd03, 4, 2000, 598)), "PowerVR Rogue GE8300", IMG, "12 nm"),
        mt("mt6765", "MediaTek Helio P35 MT6765", "MT6765", "mt6765", "6765", 8, A64_LITE, listOf(c(4, 0x41, 0, 0xd03, 4, 1800, 598), c(4, 0x41, 0, 0xd03, 4, 2300, 598)), "PowerVR Rogue GE8320", IMG, "12 nm"),
        mt("mt6771", "MediaTek Helio P60 MT6771", "MT6771", "mt6771", "6771", 8, A64_BASE, listOf(c(4, 0x41, 0, 0xd03, 4, 2000, 500), c(4, 0x41, 0, 0xd09, 2, 2000, 500)), "Mali-G72 MP3", ARM_GPU, "12 nm"),
        mt("mt6771_p70", "MediaTek Helio P70 MT6771V/CT", "MT6771", "mt6771", "6771", 8, A64_BASE, listOf(c(4, 0x41, 0, 0xd03, 4, 2000, 500), c(4, 0x41, 0, 0xd09, 2, 2100, 500)), "Mali-G72 MP3", ARM_GPU, "12 nm"),
        mt("mt6768_g85", "MediaTek Helio G85 MT6769Z", "MT6768", "mt6768", "6769", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 1800, 500), c(2, 0x41, 0, 0xd0a, 0, 2000, 500)), "Mali-G52 MC2", ARM_GPU, "12 nm"),
        mt("mt6779", "MediaTek Helio P90 MT6779", "MT6779", "mt6779", "6779", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 0, 0xd0a, 0, 2200, 500)), "PowerVR Rogue GM9446", IMG, "12 nm"),
        mt("mt6785", "MediaTek Helio G90T MT6785", "MT6785", "mt6785", "6785", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd0b, 0, 2050, 500)), "Mali-G76 MC4", ARM_GPU, "12 nm"),
        mt("mt6785_g95", "MediaTek Helio G95 MT6785", "MT6785", "mt6785", "6785", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd0b, 0, 2050, 500)), "Mali-G76 MC4", ARM_GPU, "12 nm"),
        mt("mt6781", "MediaTek Helio G96 MT6781", "MT6781", "mt6781", "6781", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd0b, 0, 2050, 500)), "Mali-G57 MC2", ARM_GPU, "12 nm"),
        mt("mt6833", "MediaTek Dimensity 700 MT6833", "MT6833", "mt6833", "6833", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd0b, 0, 2200, 500)), "Mali-G57 MC2", ARM_GPU, "7 nm"),
        mt("mt6853", "MediaTek Dimensity 720 MT6853", "MT6853", "mt6853", "6853", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd0b, 0, 2000, 500)), "Mali-G57 MC3", ARM_GPU, "7 nm"),
        mt("mt6873", "MediaTek Dimensity 800 MT6873", "MT6873", "mt6873", "6873", 8, A64_V82, listOf(c(4, 0x41, 0, 0xd05, 0, 2000, 500), c(4, 0x41, 1, 0xd0b, 0, 2000, 500)), "Mali-G57 MC4", ARM_GPU, "7 nm"),
        mt("mt6833_810", "MediaTek Dimensity 810 MT6833P", "MT6833", "mt6833", "6833", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd0b, 0, 2400, 500)), "Mali-G57 MC2", ARM_GPU, "6 nm"),
        mt("mt6877", "MediaTek Dimensity 900 MT6877", "MT6877", "mt6877", "6877", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd41, 0, 2400, 500)), "Mali-G68 MC4", ARM_GPU, "6 nm"),
        mt("mt6877_1080", "MediaTek Dimensity 1080 MT6877V", "MT6877", "mt6877", "6877", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd41, 0, 2600, 500)), "Mali-G68 MC4", ARM_GPU, "6 nm"),
        mt("mt6855", "MediaTek Dimensity 930 MT6855", "MT6855", "mt6855", "6855", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd41, 0, 2200, 500)), "PowerVR B-Series BXM-8-256", IMG, "6 nm"),
        mt("mt6893", "MediaTek Dimensity 1200 MT6893", "MT6893", "mt6893", "6893", 8, A64_V82, listOf(c(4, 0x41, 0, 0xd05, 0, 2000, 500), c(3, 0x41, 1, 0xd41, 0, 2600, 500), c(1, 0x41, 1, 0xd41, 0, 3000, 500)), "Mali-G77 MC9", ARM_GPU, "6 nm"),
        mt("mt6835", "MediaTek Dimensity 6300 MT6835", "MT6835", "mt6835", "6835", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd0b, 0, 2400, 500)), "Mali-G57 MC2", ARM_GPU, "6 nm"),
        mt("mt6886", "MediaTek Dimensity 7200 MT6886", "MT6886", "mt6886", "6886", 8, A64_V9, listOf(c(6, 0x41, 0, 0xd46, 0, 2000, 500), c(2, 0x41, 0, 0xd4d, 0, 2800, 500)), "Mali-G610 MC4", ARM_GPU, "4 nm"),
        mt("mt6895", "MediaTek Dimensity 8100 MT6895", "MT6895", "mt6895", "6895", 8, A64_V82, listOf(c(4, 0x41, 0, 0xd05, 0, 2000, 500), c(4, 0x41, 1, 0xd41, 0, 2850, 500)), "Mali-G610 MC6", ARM_GPU, "5 nm"),
        mt("mt6896", "MediaTek Dimensity 8200 MT6896", "MT6896", "mt6896", "6896", 8, A64_V82, listOf(c(4, 0x41, 0, 0xd05, 0, 2000, 500), c(3, 0x41, 1, 0xd41, 0, 3000, 500), c(1, 0x41, 1, 0xd41, 0, 3100, 500)), "Mali-G610 MC6", ARM_GPU, "4 nm"),
        mt("mt6897", "MediaTek Dimensity 8300 MT6897", "MT6897", "mt6897", "6897", 8, A64_V9, listOf(c(4, 0x41, 0, 0xd46, 0, 2200, 500), c(3, 0x41, 0, 0xd4d, 0, 3200, 500), c(1, 0x41, 0, 0xd4d, 0, 3350, 500)), "Mali-G615 MC6", ARM_GPU, "4 nm"),
        mt("mt6899", "MediaTek Dimensity 8400 MT6899", "MT6899", "mt6899", "6899", 8, A64_V9, listOf(c(4, 0x41, 0, 0xd87, 0, 2100, 500), c(3, 0x41, 0, 0xd87, 0, 3000, 500), c(1, 0x41, 0, 0xd87, 0, 3250, 500)), "Mali-G720 MC7", ARM_GPU, "4 nm"),
        mt("mt6983", "MediaTek Dimensity 9000 MT6983", "MT6983", "mt6983", "6983", 8, A64_V9, listOf(c(4, 0x41, 0, 0xd46, 0, 1800, 500), c(3, 0x41, 0, 0xd47, 0, 2850, 500), c(1, 0x41, 1, 0xd48, 0, 3050, 500)), "Mali-G710 MC10", ARM_GPU, "4 nm"),
        mt("mt6983_plus", "MediaTek Dimensity 9000+ MT6983", "MT6983", "mt6983", "6983", 8, A64_V9, listOf(c(4, 0x41, 0, 0xd46, 0, 1800, 500), c(3, 0x41, 0, 0xd47, 0, 2850, 500), c(1, 0x41, 1, 0xd48, 0, 3200, 500)), "Mali-G710 MC10", ARM_GPU, "4 nm"),
        mt("mt6985", "MediaTek Dimensity 9200 MT6985", "MT6985", "mt6985", "6985", 8, A64_V9, listOf(c(4, 0x41, 0, 0xd46, 0, 1800, 500), c(3, 0x41, 0, 0xd4d, 0, 2850, 500), c(1, 0x41, 1, 0xd4e, 0, 3050, 500)), "Immortalis-G715 MC11", ARM_GPU, "4 nm"),
        mt("mt6985_plus", "MediaTek Dimensity 9200+ MT6985", "MT6985", "mt6985", "6985", 8, A64_V9, listOf(c(4, 0x41, 0, 0xd46, 0, 2000, 500), c(3, 0x41, 0, 0xd4d, 0, 3000, 500), c(1, 0x41, 1, 0xd4e, 0, 3350, 500)), "Immortalis-G715 MC11", ARM_GPU, "4 nm"),
        mt("mt6989", "MediaTek Dimensity 9300 MT6989", "MT6989", "mt6989", "6989", 8, A64_V9, listOf(c(4, 0x41, 0, 0xd81, 0, 2000, 500), c(3, 0x41, 0, 0xd82, 0, 2850, 500), c(1, 0x41, 0, 0xd82, 0, 3250, 500)), "Immortalis-G720 MC12", ARM_GPU, "4 nm"),
        mt("mt6989_plus", "MediaTek Dimensity 9300+ MT6989", "MT6989", "mt6989", "6989", 8, A64_V9, listOf(c(4, 0x41, 0, 0xd81, 0, 2000, 500), c(3, 0x41, 0, 0xd82, 0, 2850, 500), c(1, 0x41, 0, 0xd82, 0, 3400, 500)), "Immortalis-G720 MC12", ARM_GPU, "4 nm"),
        mt("mt6991", "MediaTek Dimensity 9400 MT6991", "MT6991", "mt6991", "6991", 8, A64_V9, listOf(c(4, 0x41, 0, 0xd81, 0, 2400, 500), c(3, 0x41, 0, 0xd82, 0, 3300, 500), c(1, 0x41, 0, 0xd85, 0, 3620, 500)), "Immortalis-G925 MC12", ARM_GPU, "3 nm"),
        mt("mt6991_plus", "MediaTek Dimensity 9400+ MT6991", "MT6991", "mt6991", "6991", 8, A64_V9, listOf(c(4, 0x41, 0, 0xd81, 0, 2400, 500), c(3, 0x41, 0, 0xd82, 0, 3300, 500), c(1, 0x41, 0, 0xd85, 0, 3730, 500)), "Immortalis-G925 MC12", ARM_GPU, "3 nm"),
        // ---- added 2026-11-20 (fourth pass): Helio G / P variants and renamed Dimensity chips (same silicon as an earlier row, other clocks or name)
        mt("mt6757_p25", "MediaTek Helio P25 MT6757T", "MT6757", "mt6757", "6757", 8, A64_BASE, listOf(c(4, 0x41, 0, 0xd03, 4, 1600, 500), c(4, 0x41, 0, 0xd03, 4, 2600, 500)), "Mali-T880", ARM_GPU, "16 nm"),
        mt("mt6765_g35", "MediaTek Helio G35 MT6765G", "MT6765", "mt6765", "6765", 8, A64_LITE, listOf(c(4, 0x41, 0, 0xd03, 4, 1800, 598), c(4, 0x41, 0, 0xd03, 4, 2300, 598)), "PowerVR Rogue GE8320", IMG, "12 nm"),
        mt("mt6765_g36", "MediaTek Helio G36 MT6765", "MT6765", "mt6765", "6765", 8, A64_LITE, listOf(c(4, 0x41, 0, 0xd03, 4, 1800, 598), c(4, 0x41, 0, 0xd03, 4, 2200, 598)), "PowerVR Rogue GE8320", IMG, "12 nm"),
        mt("mt6765_g37", "MediaTek Helio G37 MT6765H", "MT6765", "mt6765", "6765", 8, A64_LITE, listOf(c(4, 0x41, 0, 0xd03, 4, 1800, 598), c(4, 0x41, 0, 0xd03, 4, 2300, 598)), "PowerVR Rogue GE8320", IMG, "12 nm"),
        mt("mt6768_g70", "MediaTek Helio G70 MT6769V/CB", "MT6768", "mt6768", "6769", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 1700, 500), c(2, 0x41, 0, 0xd0a, 0, 2000, 500)), "Mali-G52 MC2", ARM_GPU, "12 nm"),
        mt("mt6768_g80", "MediaTek Helio G80 MT6769T", "MT6768", "mt6768", "6769", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 1800, 500), c(2, 0x41, 0, 0xd0a, 0, 2000, 500)), "Mali-G52 MC2", ARM_GPU, "12 nm"),
        mt("mt6768_g88", "MediaTek Helio G88 MT6769H", "MT6768", "mt6768", "6769", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 1800, 500), c(2, 0x41, 0, 0xd0a, 0, 2000, 500)), "Mali-G52 MC2", ARM_GPU, "12 nm"),
        mt("mt6789_g100", "MediaTek Helio G100 MT6789H", "MT6789", "mt6789", "6789", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd0b, 0, 2200, 500)), "Mali-G57 MC2", ARM_GPU, "6 nm"),
        mt("mt6833_6020", "MediaTek Dimensity 6020 MT6833", "MT6833", "mt6833", "6833", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd0b, 0, 2200, 500)), "Mali-G57 MC2", ARM_GPU, "7 nm"),
        mt("mt6833_6080", "MediaTek Dimensity 6080 MT6833P", "MT6833", "mt6833", "6833", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd0b, 0, 2400, 500)), "Mali-G57 MC2", ARM_GPU, "6 nm"),
        mt("mt6855_7020", "MediaTek Dimensity 7020 MT6855", "MT6855", "mt6855", "6855", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd41, 0, 2200, 500)), "PowerVR B-Series BXM-8-256", IMG, "6 nm"),
        mt("mt6877_7050", "MediaTek Dimensity 7050 MT6877V", "MT6877", "mt6877", "6877", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd41, 0, 2600, 500)), "Mali-G68 MC4", ARM_GPU, "6 nm"),
        mt("mt6879", "MediaTek Dimensity 1050 MT6879", "MT6879", "mt6879", "6879", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd41, 0, 2500, 500)), "Mali-G610 MC3", ARM_GPU, "6 nm"),
        mt("mt6891", "MediaTek Dimensity 1100 MT6891", "MT6891", "mt6891", "6891", 8, A64_V82, listOf(c(4, 0x41, 0, 0xd05, 0, 2000, 500), c(4, 0x41, 1, 0xd41, 0, 2600, 500)), "Mali-G77 MC9", ARM_GPU, "6 nm"),
        mt("mt6895_d8000", "MediaTek Dimensity 8000 MT6895", "MT6895", "mt6895", "6895", 8, A64_V82, listOf(c(4, 0x41, 0, 0xd05, 0, 2000, 500), c(4, 0x41, 1, 0xd41, 0, 2750, 500)), "Mali-G610 MC6", ARM_GPU, "5 nm"),
    )

    // ---- per-chip data that is not a row column ---------------------------------------------------------------------
    /** soc0/foundry_id (1 TSMC, 3 Samsung) from the "Fab" column of the Wikipedia list; absent = several or other foundries */
    private val FAB = mapOf("msm8225q" to 1, "msm8210" to 1, "msm8909" to 1, "msm8226" to 1, "msm8974" to 1, "msm8916" to 1, "msm8917" to 1, "msm8940" to 1, "sdm439" to 1, "sdm450" to 3, "msm8952" to 1, "msm8953" to 3, "sdm636" to 3, "sm6115" to 3, "sm4250" to 3, "sm6225" to 1, "sm6375" to 1, "sm4350_480" to 3, "sm4350" to 3, "sm4635" to 3, "sm6475" to 3, "sm7125" to 3, "sm7150" to 3, "sm7325" to 1, "sm7325p" to 1, "sm7475" to 1, "sm7635_gen3" to 1, "sm7635" to 1, "msm8992" to 1, "msm8994" to 1, "msm8996" to 3, "msm8996l" to 3, "msm8998" to 3, "sdm845" to 3, "sm8150" to 1, "sm8250" to 1, "sm8350" to 3, "sm8450" to 3, "sm8450_2" to 3, "sm8450_3" to 3, "sm8475" to 1, "sm8475_2" to 1, "sm8550" to 1, "sm8550p" to 1, "sm8650" to 1, "sm8650p" to 1, "sm8735" to 1, "sm8750" to 1, "msm8625q" to 1, "msm8610" to 1, "msm8212" to 1, "msm8612" to 1, "msm8208" to 1, "msm8905" to 1, "apq8026" to 1, "msm8926" to 1, "msm8626" to 1, "apq8028" to 1, "msm8228" to 1, "msm8628" to 1, "msm8928" to 1, "msm8930aa" to 1, "msm8930ab" to 1, "apq8064" to 1, "apq8064ab" to 1, "apq8084" to 1, "qm215" to 1, "apq8016" to 1, "msm8936" to 1, "msm8939" to 1, "msm8929" to 1, "msm8920" to 1, "msm8937" to 1, "sdm429" to 1, "msm8956" to 1, "msm8976" to 1, "sdm630" to 3, "sdm632" to 3, "sdm660" to 3, "sm6125" to 3, "sdm670" to 3, "sdm710" to 3, "sdm712" to 3, "sm6150" to 3, "sdm850" to 3, "sm7250" to 3, "sm6350" to 3, "sm7225" to 3, "sm4375" to 1, "sm7450" to 3, "sm4450" to 3, "sm6450" to 3, "sm7550" to 1, "sm7675" to 1, "sm8635" to 1, "sm6650" to 1, "sm7750" to 1, "sm8850" to 1, "sm8950" to 1, "sm8975" to 1)
    /** top GPU clock in MHz from the same list, for /sys/class/kgsl */
    private val GPU_MHZ = mapOf("qsd8250" to 226, "msm8255" to 266, "msm8260" to 266, "msm8225" to 320, "msm8225q" to 400, "msm8960" to 400, "msm8210" to 400, "msm8909" to 400, "msm8226" to 400, "msm8974" to 450, "msm8916" to 400, "msm8917" to 598, "msm8940" to 450, "sdm439" to 650, "sdm450" to 600, "msm8952" to 550, "msm8953" to 650, "sdm636" to 430, "sm6115" to 950, "sm4250" to 600, "sm6225" to 1114, "sm6375" to 840, "sm4350_480" to 650, "sm4350" to 650, "sm6475" to 940, "sm7125" to 750, "sm7150" to 800, "sm7325" to 550, "sm7325p" to 608, "sm7475" to 580, "sm7635_gen3" to 1050, "sm7635" to 1150, "msm8992" to 600, "msm8994" to 600, "msm8996" to 624, "msm8996l" to 510, "msm8998" to 670, "sdm845" to 710, "sm8150" to 585, "sm8250" to 587, "sm8350" to 840, "sm8450" to 818, "sm8450_2" to 818, "sm8450_3" to 818, "sm8475" to 900, "sm8475_2" to 900, "sm8550" to 680, "sm8550p" to 719, "sm8650" to 903, "sm8650p" to 1000, "sm8735" to 1150, "sm8750" to 1100, "msm8660" to 266, "msm8625" to 320, "msm8625q" to 400, "msm8610" to 400, "msm8212" to 400, "msm8612" to 400, "msm8208" to 400, "msm8905" to 400, "apq8026" to 400, "msm8926" to 400, "msm8626" to 400, "apq8028" to 450, "msm8228" to 450, "msm8628" to 450, "msm8928" to 450, "msm8930" to 400, "msm8930aa" to 400, "msm8930ab" to 450, "apq8064" to 400, "apq8064ab" to 450, "apq8084" to 600, "qm215" to 485, "apq8016" to 400, "msm8936" to 550, "msm8939" to 550, "msm8929" to 465, "msm8920" to 598, "msm8937" to 450, "sdm429" to 320, "msm8956" to 600, "msm8976" to 600, "sdm630" to 650, "sdm632" to 725, "sdm660" to 647, "sm6125" to 950, "sdm670" to 430, "sdm710" to 504, "sdm712" to 610, "sm6150" to 845, "sdm850" to 710, "sm7250" to 625, "sm6350" to 565, "sm7225" to 800, "sm4375" to 700, "sm7450" to 443, "sm4450" to 955, "sm6450" to 676, "sm7550" to 975, "sm7675" to 950, "sm8635" to 1100, "sm6650" to 895, "sm7750" to 1150, "sm8850" to 1200, "sm8950" to 1250, "sm8975" to 1450)
    /** platforms whose reference kernel is 4.9 or older: they print the "Processor" line and soc0/chip_name */
    private val LEGACY = setOf("msm7x27a", "msm8660", "msm8960", "msm8974", "msm8226", "msm8610", "apq8084", "msm8909", "msm8916", "msm8937", "msm8953",
        "msm8952", "msm8976", "msm8992", "msm8994", "msm8996", "msm8998", "sdm660", "sdm845", "sdm710", "qm215", "msm7x30", "qsd8k")
    /** MediaTek chips whose reference kernel is 4.9 or older (3.18 / 4.4 / 4.9); Helio P65 / G85 and everything newer launched on 4.14+ */
    private val MTK_LEGACY = setOf("mt6580", "mt6582", "mt6592", "mt6735", "mt6755", "mt6797", "mt6757", "mt6763", "mt6758", "mt6799",
        "mt6739", "mt6761", "mt6762", "mt6765", "mt6771", "mt6771_p70", "mt6757_p25", "mt6765_g35")
    /** platforms whose Qualcomm reference board is a QRD rather than an MTP */
    private val QRD = setOf("msm7x27a", "msm8610", "msm8226", "msm8909", "msm8916", "msm8937", "msm8953", "msm8952", "qm215", "bengal", "holi", "pitti")

    private fun finish(s: SocSpec): SocSpec = if (s.manufacturer == QTI) s.copy(
        legacyKernel = s.platform in LEGACY, hwPlatform = if (s.platform in QRD) "QRD" else "MTP",
        foundryId = FAB[s.id] ?: -1, gpuMaxMhz = GPU_MHZ[s.id] ?: 0,
    ) else s.copy(legacyKernel = s.id in MTK_LEGACY)

    /** every ARM preset (Qualcomm and MediaTek), finished with the data above */
    val arm: List<SocSpec> = armRows().map(::finish)

    // Generic OEM / retail-board DMI strings; serials and the UUID are zeros (added in SocProfile.x86Files). Strings are the common
    // defaults of these boards' firmware, not one machine's.
    private val BAY_TRAIL_DMI = X86Dmi("Insyde", "BayTrail", "0.1", "", "", "Insyde", "BYT-T FFD8", "0.1", "Insyde Corp.", "BYT70.X64.0001", "08/12/2015", "",
        "Insyde", "0.1", 30)
    private val CHERRY_TRAIL_DMI = X86Dmi("Insyde", "CherryTrail", "0.1", "", "", "Insyde", "Cherry Trail CR", "0.1", "Insyde Corp.", "CHT.X64.0001", "06/03/2016", "",
        "Insyde", "0.1", 30)
    private val DIY_BOARD_DMI = X86Dmi("System manufacturer", "System Product Name", "System Version", "", "SKU", "ASUSTeK COMPUTER INC.", "PRIME B460M-A", "Rev X.0x",
        "American Megatrends Inc.", "1405", "10/22/2020", "5.17", "Default string", "Default string", 3)

    // ---- x86 and x86_64 -------------------------------------------------------------------------------------------
    // only offered to x86 / x86_64 firmware, which is never offered the ARM rows above
    val x86: List<SocSpec> = listOf(
        // No ro.board.platform here: an x86 firmware finds its graphics / camera libraries by the platform name it was built with
    //    id         label                    brand string                                       cpuid-model step L2-KB cores threads maxMHz flags  phys virt  gpu vendor / GL_RENDERER   node
        atom("z2460", "Intel Atom Z2460",   "Intel(R) Atom(TM) CPU  Z2460  @ 1.60GHz",   39, 2,   512, 1, 2, 1600, SALTWELL,   32, 32, IMG, "PowerVR SGX 540", "32 nm"),
        atom("z2480", "Intel Atom Z2480",   "Intel(R) Atom(TM) CPU  Z2480  @ 2.00GHz",   39, 2,   512, 1, 2, 2000, SALTWELL,   32, 32, IMG, "PowerVR SGX 540", "32 nm"),
        atom("z2610", "Intel Atom Z2610",   "Intel(R) Atom(TM) CPU  Z2610  @ 1.20GHz",   39, 2,   512, 1, 2, 1200, SALTWELL,   32, 32, IMG, "PowerVR SGX 540", "32 nm"),
        atom("z2420", "Intel Atom Z2420",   "Intel(R) Atom(TM) CPU  Z2420  @ 1.20GHz",   39, 2,   512, 1, 2, 1200, SALTWELL,   32, 32, IMG, "PowerVR SGX 540", "32 nm"),
        atom("z2580", "Intel Atom Z2580",   "Intel(R) Atom(TM) CPU  Z2580  @ 2.00GHz",   53, 1,   512, 2, 4, 2000, SALTWELL,   32, 32, IMG, "PowerVR SGX 544MP2", "32 nm"),
        atom("z2760", "Intel Atom Z2760",   "Intel(R) Atom(TM) CPU  Z2760  @ 1.80GHz",   53, 1,   512, 2, 4, 1800, SALTWELL,   32, 32, IMG, "PowerVR SGX 545", "32 nm"),
        atom("z3480", "Intel Atom Z3480",   "Intel(R) Atom(TM) CPU  Z3480  @ 2.13GHz",   74, 1,  1024, 2, 2, 2130, SILVERMONT, 36, 48, IMG, "PowerVR Rogue G6400", "22 nm"),
        atom("z3460", "Intel Atom Z3460",   "Intel(R) Atom(TM) CPU  Z3460  @ 1.60GHz",   74, 1,  1024, 2, 2, 1600, SILVERMONT, 36, 48, IMG, "PowerVR Rogue G6400", "22 nm"),
        atom("z3530", "Intel Atom Z3530",   "Intel(R) Atom(TM) CPU  Z3530  @ 1.33GHz",   90, 0,  1024, 4, 4, 1330, SILVERMONT, 36, 48, IMG, "PowerVR Rogue G6430", "22 nm"),
        atom("z3560", "Intel Atom Z3560",   "Intel(R) Atom(TM) CPU  Z3560  @ 1.00GHz",   90, 0,  1024, 4, 4, 1830, SILVERMONT, 36, 48, IMG, "PowerVR Rogue G6430", "22 nm"),
        atom("z3570", "Intel Atom Z3570",   "Intel(R) Atom(TM) CPU  Z3570  @ 1.33GHz",   90, 0,  1024, 4, 4, 2000, SILVERMONT, 36, 48, IMG, "PowerVR Rogue G6430", "22 nm"),
        atom("z3590", "Intel Atom Z3590",   "Intel(R) Atom(TM) CPU  Z3590  @ 2.50GHz",   90, 0,  1024, 4, 4, 2500, SILVERMONT, 36, 48, IMG, "PowerVR Rogue G6430", "22 nm"),
        atom("z3735f", "Intel Atom Z3735F", "Intel(R) Atom(TM) CPU  Z3735F @ 1.33GHz",  55, 8,  1024, 4, 4, 1830, SILVERMONT, 36, 48, MESA, "Mesa DRI Intel(R) Bay Trail", "22 nm"),
        atom("z3795", "Intel Atom Z3795",   "Intel(R) Atom(TM) CPU  Z3795  @ 1.59GHz",   55, 8,  1024, 4, 4, 2390, SILVERMONT, 36, 48, MESA, "Mesa DRI Intel(R) Bay Trail", "22 nm"),
        atom("x3c3130", "Intel Atom x3-C3130", "Intel(R) Atom(TM) x3-C3130",            93, 1,   512, 2, 2, 1000, SILVERMONT, 36, 48, ARM_GPU, "Mali-400 MP2", "28 nm"),
        atom("x3c3200rk", "Intel Atom x3-C3200RK", "Intel(R) Atom(TM) x3-C3200RK",      93, 1,  1024, 4, 4, 1100, SILVERMONT, 36, 48, ARM_GPU, "Mali-450 MP4", "28 nm"),
        atom("x3c3205rk", "Intel Atom x3-C3205RK", "Intel(R) Atom(TM) x3-C3205RK",      93, 1,  1024, 4, 4, 1100, SILVERMONT, 36, 48, ARM_GPU, "Mali-450 MP4", "28 nm"),
        atom("x3c3230rk", "Intel Atom x3-C3230RK", "Intel(R) Atom(TM) x3-C3230RK",      93, 1,  1024, 4, 4, 1100, SILVERMONT, 36, 48, ARM_GPU, "Mali-450 MP4", "28 nm"),
        atom("x3c3235rk", "Intel Atom x3-C3235RK", "Intel(R) Atom(TM) x3-C3235RK",      93, 1,  1024, 4, 4, 1200, SILVERMONT, 36, 48, ARM_GPU, "Mali-450 MP4", "28 nm"),
        atom("x3c3265rk", "Intel Atom x3-C3265RK", "Intel(R) Atom(TM) x3-C3265RK",      93, 1,  1024, 4, 4, 1100, SILVERMONT, 36, 48, ARM_GPU, "Mali-450 MP4", "28 nm"),
        atom("x3c3295rk", "Intel Atom x3-C3295RK", "Intel(R) Atom(TM) x3-C3295RK",      93, 1,  1024, 4, 4, 1100, SILVERMONT, 36, 48, ARM_GPU, "Mali-450 MP4", "28 nm"),
        atom("x3c3405", "Intel Atom x3-C3405", "Intel(R) Atom(TM) x3-C3405",            93, 1,  1024, 4, 4, 1400, SILVERMONT, 36, 48, ARM_GPU, "Mali-T720", "28 nm"),
        atom("x3c3440", "Intel Atom x3-C3440", "Intel(R) Atom(TM) x3-C3440",            93, 1,  1024, 4, 4, 1400, SILVERMONT, 36, 48, ARM_GPU, "Mali-T720", "28 nm"),
        atom("x3c3445", "Intel Atom x3-C3445", "Intel(R) Atom(TM) x3-C3445",            93, 1,  1024, 4, 4, 1400, SILVERMONT, 36, 48, ARM_GPU, "Mali-T720", "28 nm"),
        atom("x5z8300", "Intel Atom x5-Z8300", "Intel(R) Atom(TM) x5-Z8300  CPU @ 1.44GHz", 76, 3, 1024, 4, 4, 1840, CHERRY_TRAIL, 36, 48, MESA, "Mesa DRI Intel(R) HD Graphics (Cherryview)", "14 nm"),
        // desktop pair, for SDK emulators (an x86 image on a PC GPU)
        desktop("i5_10400f_gtx1650", "Intel Core i5-10400F + GTX 1650", "Intel(R) Core(TM) i5-10400F CPU @ 2.90GHz", 165, 3, 12288, 6, 12, 4300, COMET_LAKE, 39, 48,
            "NVIDIA Corporation", "NVIDIA GeForce GTX 1650/PCIe/SSE2", "14 nm"),
    )

    // ---- feature strings -------------------------------------------------------------------------------------------
    private const val SCORPION = "swp half thumb fastmult vfp edsp neon vfpv3 tls"
    private const val A5_V7 = "swp half thumb fastmult vfp edsp neon vfpv3 tls vfpv4"
    private const val A7_V7 = "swp half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt vfpd32 lpae evtstrm"
    private const val KRAIT = "swp half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt"
    private const val V7 = "half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt vfpd32 lpae evtstrm"
    private const val A64_BASE = "fp asimd evtstrm aes pmull sha1 sha2 crc32"
    private const val A64_LITE = "fp asimd evtstrm crc32"
    private const val A64_V82 = "$A64_BASE atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp"
    private const val A64_V9 = "$A64_V82 sha3 sm3 sm4 sha512 sve asimdfhm dit uscat ilrcpc flagm ssbs sb paca pacg dcpodp sve2 sveaes svepmull svebitperm svei8mm svebf16 i8mm bf16 dgh bti"
    private const val A64_ORYON = "$A64_V82 jscvt fcma sha3 sm3 sm4 sha512 asimdfhm dit uscat ilrcpc flagm ssbs sb paca pacg dcpodp flagm2 frint i8mm bf16 dgh bti"

    private const val X86_BASE = "fpu vme de pse tsc msr pae mce cx8 apic sep mtrr pge mca cmov pat pse36 clflush dts acpi mmx fxsr sse sse2 ss ht tm pbe"
    private const val SALTWELL = "fpu vme de pse tsc msr pae mce cx8 apic sep mtrr pge mca cmov pat pse36 clflush dts acpi mmx fxsr sse sse2 ss ht tm pbe nx constant_tsc arch_perfmon pebs bts nopl nonstop_tsc aperfmperf pni dtes64 monitor ds_cpl est tm2 ssse3 xtpr pdcm movbe lahf_lm arat dtherm"
    // the Z3580's own line (zenfone-2 dump in AOSP external/cpuinfo): vmx, ida and the EPT group included
    private const val SILVERMONT = "$X86_BASE syscall nx rdtscp lm constant_tsc arch_perfmon pebs bts rep_good nopl xtopology nonstop_tsc aperfmperf pni pclmulqdq dtes64 monitor ds_cpl vmx est tm2 ssse3 cx16 xtpr pdcm sse4_1 sse4_2 movbe popcnt tsc_deadline_timer aes rdrand lahf_lm 3dnowprefetch ida arat epb dtherm tpr_shadow vnmi flexpriority ept vpid tsc_adjust smep erms"
    // x5-Z8300 on a 4.15 kernel (Lemel stick dump, Ubuntu 18.04): tsc_reliable / cpuid / tsc_known_freq / pti appear, ida does not
    private const val CHERRY_TRAIL = "fpu vme de pse tsc msr pae mce cx8 apic sep mtrr pge mca cmov pat pse36 clflush dts acpi mmx fxsr sse sse2 ss ht tm pbe syscall nx rdtscp lm constant_tsc arch_perfmon pebs bts rep_good nopl xtopology tsc_reliable nonstop_tsc cpuid aperfmperf tsc_known_freq pni pclmulqdq dtes64 monitor ds_cpl vmx est tm2 ssse3 cx16 xtpr pdcm sse4_1 sse4_2 movbe popcnt tsc_deadline_timer aes rdrand lahf_lm 3dnowprefetch epb pti tpr_shadow vnmi flexpriority ept vpid tsc_adjust smep erms dtherm arat"
    private const val COMET_LAKE = "$X86_BASE syscall nx pdpe1gb rdtscp lm constant_tsc art arch_perfmon pebs bts rep_good nopl xtopology nonstop_tsc cpuid aperfmperf pni pclmulqdq dtes64 monitor ds_cpl vmx smx est tm2 ssse3 sdbg fma cx16 xtpr pdcm pcid sse4_1 sse4_2 x2apic movbe popcnt tsc_deadline_timer aes xsave avx f16c rdrand lahf_lm abm 3dnowprefetch cpuid_fault epb invpcid_single ssbd ibrs ibpb stibp ibrs_enhanced tpr_shadow vnmi flexpriority ept vpid ept_ad fsgsbase tsc_adjust bmi1 avx2 smep bmi2 erms invpcid mpx rdseed adx smap clflushopt intel_pt xsaveopt xsavec xgetbv1 xsaves dtherm ida arat pln pts hwp hwp_notify hwp_act_window hwp_epp md_clear flush_l1d arch_capabilities"

    private const val IMG = "Imagination Technologies"
    private const val MESA = "Intel Open Source Technology Center"
    private const val ARM_GPU = "ARM"

    // ---- row builders ----------------------------------------------------------------------------------------------

    /** (count, implementer, variant, part, revision, max MHz, min MHz); little cores first */
    private fun c(count: Int, implementer: Int, variant: Int, part: Int, revision: Int, maxMhz: Int, minMhz: Int) =
        SocCluster(count, implementer, variant, part, revision, maxMhz, minMhz)

    /** Qualcomm: id, menu name, ro.soc.model, ro.board.platform, soc0 soc_id, ARM version, Features, clusters, GL_RENDERER, process node */
    private fun qc(id: String, label: String, model: String, platform: String, socId: String, arch: Int, features: String, clusters: List<SocCluster>,
                   gpu: String, node: String, hw: String = "", rev: String = "1.0", serial: String = "", bogo: String = ""): SocSpec {
        val name = model.substringBefore('-')
        return SocSpec(
            id = id, label = label, manufacturer = QTI, model = model, platform = platform,
            hardware = hw.ifEmpty { if (arch >= 8) "Qualcomm Technologies, Inc $name" else "Qualcomm $name" },
            family = "Snapdragon", machine = model, socId = socId, revision = rev, serialNumber = serial.ifEmpty { fakeSerial(id) },
            arch = arch, clusters = clusters, features = features, bogoMips = bogo,
            isa = SocIsa.ARM, gpuVendor = "Qualcomm", gpuRenderer = gpu, note = node,
        )
    }

    /** MediaTek: id, menu name, model, platform, soc_id, ARM version, Features, clusters, GL_RENDERER, GL_VENDOR, node */
    private fun mt(id: String, label: String, model: String, platform: String, socId: String, arch: Int, features: String, clusters: List<SocCluster>,
                   gpu: String, gpuVendor: String, node: String, serial: String = "", bogo: String = "26.00") = SocSpec(
        id = id, label = label, manufacturer = "MediaTek", model = model, platform = platform, hardware = model,
        family = "MediaTek", machine = model, socId = socId, revision = "1.0", serialNumber = serial.ifEmpty { fakeSerial(id) },
        arch = arch, clusters = clusters, features = features, bogoMips = bogo,
        isa = SocIsa.ARM, gpuVendor = gpuVendor, gpuRenderer = gpu, note = node,
    )

    /** Intel Atom: id, menu name, brand string, cpuid model, stepping, L2 KB, cores, threads, max MHz, flags, address bits, GL_VENDOR, GL_RENDERER, node */
    private fun atom(id: String, label: String, brand: String, model: Int, stepping: Int, cacheKb: Int, cores: Int, threads: Int, maxMhz: Int, flags: String,
                     phys: Int, virt: Int, gpuVendor: String, gpu: String, node: String): SocSpec {
        val saltwell = flags == SALTWELL
        val bay = model == 55
        val cht = model == 76
        // Bay / Cherry Trail boards are UEFI machines (DMI, intel_pstate, a PCI HD Graphics); the phone Atoms boot a 3.x BSP kernel without them
        val pci = when { bay -> "0x8086:0x0f31"; cht -> "0x8086:0x22b1"; else -> "" }
        val dmi = when { bay -> BAY_TRAIL_DMI; cht -> CHERRY_TRAIL_DMI; else -> null }
        val x86 = X86Cpu(
            vendorId = "GenuineIntel", family = 6, model = model, stepping = stepping, modelName = brand, cacheKb = cacheKb, physicalCores = cores, flags = flags,
            physBits = phys, virtBits = virt,
            // microcode from real dumps: Z3580 0x34 / 0x38 (pytorch/cpuinfo zenfone-2), Z3560 0x34, Z3735F 0x82b / 0x82f / 0x838, x5-Z8300 0x35f / 0x363 / 0x368.
            // Merrifield (Z34xx) and Saltwell (0x218) values are NOT from a dump: plausible only
            microcode = when { saltwell -> "0x218"; bay -> "0x838"; cht -> "0x368"; else -> "0x38" },
            cpuidLevel = if (saltwell) 10 else 11,
            uarch = when { saltwell -> "saltwell"; cht -> "airmont"; else -> "silvermont" },
            cpufreqDriver = when { bay || cht -> "intel_pstate"; saltwell -> "acpi-cpufreq"; model == 93 -> "acpi-cpufreq"; else -> "sfi-cpufreq" },
            // x5-Z8300 dump (OpenBenchmarking, kernel with PTI): Silvermont / Airmont are Meltdown + MSBDS affected
            bugs = if (dmi != null) "cpu_meltdown spectre_v1 spectre_v2 mds msbds_only" else "",
            vmxFlags = if (dmi != null) "vnmi preemption_timer invvpid ept_x_only flexpriority tsc_offset vtpr mtf vapic ept vpid unrestricted_guest" else "",
            gpuPci = pci, dmi = dmi,
        )
        return SocSpec(
            id = id, label = label, manufacturer = "Intel", model = label.removePrefix("Intel Atom ").trim(), platform = "",
            hardware = "", isa = SocIsa.X86,
            clusters = listOf(SocCluster(threads, 0, 0, 0, 0, maxMhz, 800)),
            x86 = x86, gpuVendor = gpuVendor, gpuRenderer = gpu, note = node, legacyKernel = dmi == null,
        )
    }

    /** a desktop CPU + graphics card (no platform property: the firmware's own stays) */
    private fun desktop(id: String, label: String, brand: String, model: Int, stepping: Int, cacheKb: Int, cores: Int, threads: Int, maxMhz: Int, flags: String,
                        phys: Int, virt: Int, gpuVendor: String, gpu: String, node: String) = SocSpec(
        id = id, label = label, manufacturer = "Intel", model = "i5-10400F", platform = "",
        hardware = "", isa = SocIsa.X86,
        clusters = listOf(SocCluster(threads, 0, 0, 0, 0, maxMhz, 800)),
        x86 = X86Cpu(
            vendorId = "GenuineIntel", family = 6, model = model, stepping = stepping, modelName = brand, cacheKb = cacheKb, physicalCores = cores, flags = flags,
            physBits = phys, virtBits = virt, microcode = "0xf8", cpuidLevel = 22,
            bugs = "spectre_v1 spectre_v2 spec_store_bypass swapgs itlb_multihit srbds mmio_stale_data",
            vmxFlags = "vnmi preemption_timer posted_intr invvpid ept_x_only ept_ad ept_1gb flexpriority apicv tsc_offset vtpr mtf vapic ept vpid unrestricted_guest vapic_reg vid ple shadow_vmcs pml",
            uarch = "desktop", cpufreqDriver = "intel_pstate", gpuPci = "0x10de:0x1f82", dmi = DIY_BOARD_DMI,
        ),
        gpuVendor = gpuVendor, gpuRenderer = gpu, note = node,
    )

    /** a fixed serial per chip, so the guest sees the same one at every boot */
    private fun fakeSerial(id: String): String = ((id.hashCode().toLong() and 0x7fffffffL) * 1_000_003L % 4_000_000_000L).toString()
}
