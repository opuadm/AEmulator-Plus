/* Added for AEmulator Plus, 2026-11-21. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

/**
 * The kind of memory the guest says it has (VM settings > device identity > RAM type). [id] is what VmSettings.ramType holds
 * ("" = the guest's default: nothing is reported). Only the name changes; the amount is the "Guest RAM" setting.
 */
enum class RamType(val id: String, val label: String, val ddr: Int) {
    LPDDR2("lpddr2", "LPDDR2", 2),
    LPDDR3("lpddr3", "LPDDR3", 3),
    LPDDR4("lpddr4", "LPDDR4", 4),
    LPDDR4X("lpddr4x", "LPDDR4X", 4),
    LPDDR5("lpddr5", "LPDDR5", 5),
    LPDDR5X("lpddr5x", "LPDDR5X", 5);

    companion object {
        fun parse(id: String): RamType? = entries.firstOrNull { it.id == id }
    }
}

object RamSpoof {
    /** Properties that tell the guest which memory it has (there is no standard one: vendors and info apps read different names). */
    fun props(type: RamType?): Map<String, String> = if (type == null) emptyMap() else linkedMapOf(
        "ro.boot.ddr_type" to type.label,
        "ro.boot.dram_type" to type.label,
        "ro.boot.ram_type" to type.label,
        "ro.vendor.ram_type" to type.label,
        "ro.hardware.ram_type" to type.label,
    )
}
