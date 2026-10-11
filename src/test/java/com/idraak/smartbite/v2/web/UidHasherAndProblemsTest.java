package com.idraak.smartbite.v2.web;

import com.idraak.smartbite.v2.error.Problems;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UidHasherAndProblemsTest {

    @Test
    void hashIsStableShortAndNotTheUid() {
        UidHasher h = new UidHasher("dGVzdC1rZXk=");
        assertThat(h.hash("uid-123")).hasSize(16).isEqualTo(h.hash("uid-123")).doesNotContain("uid");
        assertThat(h.hash("uid-124")).isNotEqualTo(h.hash("uid-123"));
        assertThat(new UidHasher("b3RoZXIta2V5").hash("uid-123")).isNotEqualTo(h.hash("uid-123"));
    }

    @Test
    void snakeCasesValidationPaths() {
        assertThat(Problems.snakeCasePath("area.radiusKm")).isEqualTo("area.radius_km");
        assertThat(Problems.snakeCasePath("history[2].entryType")).isEqualTo("history[2].entry_type");
        assertThat(Problems.snakeCasePath("timezone")).isEqualTo("timezone");
    }
}
