package io.github.mayusi.calibratesoc.data.hardware

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SocFriendlyNamesTest {

    @Test
    fun `Odin 3 reports CQ8725S maps to Snapdragon 8 Elite`() {
        val e = SocFriendlyNames.lookup("CQ8725S")
        assertThat(e).isNotNull()
        assertThat(e!!.friendly).isEqualTo("Snapdragon 8 Elite")
        assertThat(e.gpu).isEqualTo("Adreno 830")
    }

    @Test
    fun `Thor reports QCS8550 maps to Snapdragon 8 Gen 2`() {
        // The AYN Thor ships the embedded QCS8550 SKU, not the
        // commercial SM8550. Both are the same silicon (8 Gen 2).
        // Regression guard: before the fix this returned null and the
        // UI showed the raw "QCS8550" codename.
        val e = SocFriendlyNames.lookup("QCS8550")
        assertThat(e).isNotNull()
        assertThat(e!!.friendly).isEqualTo("Snapdragon 8 Gen 2")
        assertThat(e.gpu).isEqualTo("Adreno 740")
    }

    @Test
    fun `commercial SM8550 still maps to Snapdragon 8 Gen 2`() {
        val e = SocFriendlyNames.lookup("SM8550")
        assertThat(e!!.friendly).isEqualTo("Snapdragon 8 Gen 2")
    }

    @Test
    fun `SG8275 maps to Snapdragon G3x Gen 2`() {
        // The SG8275 gaming SKU = G3x Gen 2. Chip detection retained
        // even though the Pocket DS device adapter was removed.
        val e = SocFriendlyNames.lookup("SG8275")
        assertThat(e).isNotNull()
        assertThat(e!!.friendly).isEqualTo("Snapdragon G3x Gen 2")
        assertThat(e.gpu).isEqualTo("Adreno A32")
    }

    @Test
    fun `unknown codename returns null`() {
        assertThat(SocFriendlyNames.lookup("TOTALLY_MADE_UP")).isNull()
        assertThat(SocFriendlyNames.lookup("")).isNull()
    }
}

class StorageClassNamesTest {

    @Test
    fun `Thor SK Hynix HN8T chip maps to UFS 4 by model`() {
        val e = StorageClassNames.lookup("HN8T374ZJKX141")
        assertThat(e).isNotNull()
        assertThat(e!!.vendor).isEqualTo("SK Hynix")
        assertThat(e.storageClass).isEqualTo("UFS 4.0")
    }

    @Test
    fun `RP6 YMTC YMUS9 chip maps to UFS 4 by model`() {
        val e = StorageClassNames.lookup("YMUS9B4TF2D1C1")
        assertThat(e).isNotNull()
        assertThat(e!!.vendor).isEqualTo("YMTC")
        assertThat(e.storageClass).isEqualTo("UFS 4.0")
    }

    @Test
    fun `device fallback covers Thor and RP6 when sysfs is blocked`() {
        assertThat(StorageClassNames.lookupByDevice("AYN Thor")?.storageClass)
            .isEqualTo("UFS 4.0")
        assertThat(StorageClassNames.lookupByDevice("Retroid Pocket 6")?.vendor)
            .isEqualTo("YMTC")
        assertThat(StorageClassNames.lookupByDevice("Odin3")?.vendor)
            .isEqualTo("Kioxia")
    }
}
