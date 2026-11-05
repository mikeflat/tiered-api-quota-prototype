package com.mikeflat.gateway.app.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mikeflat.gateway.domain.tenant.Tenant;
import com.mikeflat.gateway.domain.tenant.TenantLoadException;
import com.mikeflat.gateway.domain.tenant.TenantRegistry;
import com.mikeflat.gateway.domain.tenant.TenantRepository;
import com.mikeflat.gateway.domain.tenant.Tier;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class LoadTenantRegistryTest {

    private static final String SOURCE = "/config/tenants.json";

    private static final class FakeTenantRepository implements TenantRepository {
        private final Supplier<List<Tenant>> result;
        private int calls;

        FakeTenantRepository(Supplier<List<Tenant>> result) {
            this.result = result;
        }

        @Override
        public List<Tenant> findAll() {
            calls++;
            return result.get();
        }

        @Override
        public String description() {
            return SOURCE;
        }
    }

    @Test
    void builds_registry_from_repository() {
        FakeTenantRepository repository = new FakeTenantRepository(() -> List.of(
                new Tenant(1, "Acme Corp", Tier.BRONZE),
                new Tenant(14, "Nimbus Cloud", Tier.GOLD)));

        TenantRegistry registry = new LoadTenantRegistry(repository).load();

        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.find(14)).map(Tenant::tier).contains(Tier.GOLD);
        assertThat(repository.calls).isEqualTo(1);
    }

    @Test
    void empty_list_wrapped_naming_the_source() {
        FakeTenantRepository repository = new FakeTenantRepository(List::of);

        assertThatThrownBy(() -> new LoadTenantRegistry(repository).load())
                .isInstanceOf(TenantLoadException.class)
                .hasMessageContaining(SOURCE)
                .hasMessageContaining("no tenants")
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(((TenantLoadException) e).source()).isEqualTo(SOURCE));
    }

    @Test
    void duplicate_id_wrapped_naming_the_source_and_id() {
        FakeTenantRepository repository = new FakeTenantRepository(() -> List.of(
                new Tenant(1, "Acme Corp", Tier.BRONZE),
                new Tenant(1, "Acme Again", Tier.SILVER)));

        assertThatThrownBy(() -> new LoadTenantRegistry(repository).load())
                .isInstanceOf(TenantLoadException.class)
                .hasMessageContaining(SOURCE)
                .hasMessageContaining("duplicate tenant id 1");
    }

    @Test
    void repository_exception_passed_through_unchanged() {
        TenantLoadException failure = new TenantLoadException(SOURCE, "file not found");
        FakeTenantRepository repository = new FakeTenantRepository(() -> {
            throw failure;
        });

        assertThatThrownBy(() -> new LoadTenantRegistry(repository).load()).isSameAs(failure);
    }

    @Test
    void source_description_comes_from_repository() {
        assertThat(new LoadTenantRegistry(new FakeTenantRepository(List::of)).sourceDescription())
                .isEqualTo(SOURCE);
    }
}
