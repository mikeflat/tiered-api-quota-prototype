package com.mikeflat.gateway.adapters.persistence.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mikeflat.gateway.domain.tenant.Tenant;
import com.mikeflat.gateway.domain.tenant.TenantLoadException;
import com.mikeflat.gateway.domain.tenant.Tier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonFileTenantRepositoryTest {

    @TempDir
    Path dir;

    private Path write(String json) throws IOException {
        Path file = dir.resolve("tenants.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    private AbstractThrowableAssert<?, ? extends Throwable> assertLoadFails(Path file) {
        return assertThatThrownBy(() -> new JsonFileTenantRepository(file).findAll())
                .isInstanceOf(TenantLoadException.class)
                .hasMessageContaining(file.toString());
    }

    @Test
    void reads_valid_file_in_order() throws IOException {
        Path file = write("""
                [
                  { "id": 1, "name": "Acme Corp", "tier": "Bronze" },
                  { "id": 11, "name": "Kestrel Payments", "tier": "Silver" },
                  { "id": 14, "name": "Nimbus Cloud", "tier": "Gold" }
                ]
                """);

        assertThat(new JsonFileTenantRepository(file).findAll()).containsExactly(
                new Tenant(1, "Acme Corp", Tier.BRONZE),
                new Tenant(11, "Kestrel Payments", Tier.SILVER),
                new Tenant(14, "Nimbus Cloud", Tier.GOLD));
    }

    @Test
    void description_is_the_path() {
        Path file = dir.resolve("tenants.json");

        assertThat(new JsonFileTenantRepository(file).description()).isEqualTo(file.toString());
    }

    @Test
    void lower_case_tier_accepted() throws IOException {
        Path file = write("""
                [{ "id": 10, "name": "Juniper Media", "tier": "silver" }]
                """);

        assertThat(new JsonFileTenantRepository(file).findAll())
                .containsExactly(new Tenant(10, "Juniper Media", Tier.SILVER));
    }

    @Test
    void extra_field_ignored() throws IOException {
        Path file = write("""
                [{ "id": 1, "name": "Acme Corp", "tier": "Bronze", "comment": "demo" }]
                """);

        assertThat(new JsonFileTenantRepository(file).findAll())
                .containsExactly(new Tenant(1, "Acme Corp", Tier.BRONZE));
    }

    @Test
    void largest_long_id_accepted() throws IOException {
        Path file = write("""
                [{ "id": 9223372036854775807, "name": "Acme Corp", "tier": "Bronze" }]
                """);

        assertThat(new JsonFileTenantRepository(file).findAll())
                .containsExactly(new Tenant(Long.MAX_VALUE, "Acme Corp", Tier.BRONZE));
    }

    @Test
    void empty_array_returns_empty_list() throws IOException {
        assertThat(new JsonFileTenantRepository(write("[]")).findAll()).isEmpty();
    }

    @Test
    void missing_file_names_the_path() {
        Path missing = dir.resolve("nope").resolve("tenants.json");

        assertLoadFails(missing).hasMessageContaining("not found");
    }

    @Test
    void directory_instead_of_file_rejected() {
        assertLoadFails(dir).hasMessageContaining("not a regular file");
    }

    @Test
    void malformed_json_rejected() throws IOException {
        assertLoadFails(write("[{\"id\": 1,")).hasMessageContaining("not valid JSON");
    }

    @Test
    void empty_file_rejected() throws IOException {
        assertLoadFails(write("")).hasMessageContaining("array");
    }

    @Test
    void top_level_not_an_array_rejected() throws IOException {
        assertLoadFails(write("{\"tenants\": []}")).hasMessageContaining("array");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[\"acme\"]", "[42]", "[null]", "[[]]"})
    void entry_not_an_object_rejected(String json) throws IOException {
        assertLoadFails(write(json))
                .hasMessageContaining("entry 0")
                .hasMessageContaining("object");
    }

    @Test
    void missing_name_rejected_naming_entry_and_field() throws IOException {
        Path file = write("""
                [
                  { "id": 1, "name": "Acme Corp", "tier": "Bronze" },
                  { "id": 3, "tier": "Bronze" }
                ]
                """);

        assertLoadFails(file)
                .hasMessageContaining("entry 1 (id 3)")
                .hasMessageContaining("'name'");
    }

    @Test
    void blank_name_rejected_naming_field() throws IOException {
        assertLoadFails(write("[{ \"id\": 1, \"name\": \"  \", \"tier\": \"Bronze\" }]"))
                .hasMessageContaining("entry 0 (id 1)")
                .hasMessageContaining("'name'");
    }

    @Test
    void missing_id_rejected_naming_field() throws IOException {
        assertLoadFails(write("[{ \"name\": \"Acme Corp\", \"tier\": \"Bronze\" }]"))
                .hasMessageContaining("entry 0")
                .hasMessageContaining("'id'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"1\"", "1.5", "true", "\"acme\"", "99999999999999999999"})
    void id_that_is_not_a_whole_number_rejected(String id) throws IOException {
        assertLoadFails(write("[{ \"id\": " + id + ", \"name\": \"Acme Corp\", \"tier\": \"Bronze\" }]"))
                .hasMessageContaining("entry 0")
                .hasMessageContaining("'id'")
                .hasMessageContaining("whole number");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -3})
    void non_positive_id_rejected_naming_value(long id) throws IOException {
        assertLoadFails(write("[{ \"id\": " + id + ", \"name\": \"Acme Corp\", \"tier\": \"Bronze\" }]"))
                .hasMessageContaining("entry 0")
                .hasMessageContaining("positive")
                .hasMessageContaining(Long.toString(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"42", "true", "{}"})
    void name_that_is_not_a_string_rejected(String name) throws IOException {
        assertLoadFails(write("[{ \"id\": 1, \"name\": " + name + ", \"tier\": \"Bronze\" }]"))
                .hasMessageContaining("entry 0")
                .hasMessageContaining("'name'")
                .hasMessageContaining("string");
    }

    @Test
    void missing_tier_rejected_naming_field() throws IOException {
        assertLoadFails(write("[{ \"id\": 1, \"name\": \"Acme Corp\" }]"))
                .hasMessageContaining("entry 0 (id 1)")
                .hasMessageContaining("'tier'");
    }

    @Test
    void unknown_tier_rejected_naming_entry_and_value() throws IOException {
        assertLoadFails(write("[{ \"id\": 1, \"name\": \"Acme Corp\", \"tier\": \"Platinum\" }]"))
                .hasMessageContaining("entry 0 (id 1)")
                .hasMessageContaining("Platinum");
    }
}
