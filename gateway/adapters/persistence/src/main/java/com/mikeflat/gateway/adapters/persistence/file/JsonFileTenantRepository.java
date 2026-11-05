package com.mikeflat.gateway.adapters.persistence.file;

import com.mikeflat.gateway.domain.tenant.Tenant;
import com.mikeflat.gateway.domain.tenant.TenantLoadException;
import com.mikeflat.gateway.domain.tenant.TenantRepository;
import com.mikeflat.gateway.domain.tenant.Tier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.InputCoercionException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

public final class JsonFileTenantRepository implements TenantRepository {

    private record TenantEntry(Long id, String name, String tier) {
    }

    private static final TypeReference<List<TenantEntry>> TENANT_ENTRIES = new TypeReference<>() {
    };

    private static final JsonMapper STRICT_SCALAR_MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .withCoercionConfig(LogicalType.Integer, config -> config
                    .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .withCoercionConfig(LogicalType.Textual, config -> config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .build();

    private final Path file;

    public JsonFileTenantRepository(Path file) {
        this.file = file;
    }

    @Override
    public String description() {
        return file.toString();
    }

    @Override
    public List<Tenant> findAll() {
        List<TenantEntry> entries = bind(readBytes());
        List<Tenant> tenants = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            tenants.add(toTenant(index, entries.get(index)));
        }
        return tenants;
    }

    private byte[] readBytes() {
        if (Files.exists(file) && !Files.isRegularFile(file)) {
            throw fail("not a regular file");
        }
        try {
            return Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            throw fail("file not found", e);
        } catch (IOException e) {
            throw fail("file cannot be read (" + e.getMessage() + ")", e);
        }
    }

    private List<TenantEntry> bind(byte[] content) {
        try {
            List<TenantEntry> entries = STRICT_SCALAR_MAPPER.readValue(content, TENANT_ENTRIES);
            if (entries == null) {
                throw fail(expectedArray());
            }
            return entries;
        } catch (DatabindException | InputCoercionException e) {
            throw fail(describeBindingFailure(e), e);
        } catch (JacksonException e) {
            throw fail("not valid JSON (" + e.getOriginalMessage() + ")", e);
        }
    }

    private static String describeBindingFailure(JacksonException e) {
        List<JacksonException.Reference> path = e.getPath();
        if (path.isEmpty()) {
            return expectedArray();
        }
        int index = path.get(0).getIndex();
        if (path.size() == 1) {
            return entryNotAnObject(index);
        }
        String field = path.get(1).getPropertyName();
        String expected = "id".equals(field) ? "a whole number" : "a string";
        return "entry " + index + ": field '" + field + "' must be " + expected;
    }

    private static String expectedArray() {
        return "expected a JSON array of tenant objects at the top level";
    }

    private static String entryNotAnObject(int index) {
        return "entry " + index + " must be an object with 'id', 'name' and 'tier'";
    }

    private Tenant toTenant(int index, TenantEntry entry) {
        if (entry == null) {
            throw fail(entryNotAnObject(index));
        }
        if (entry.id() == null) {
            throw fail("entry " + index + ": field 'id' is missing");
        }
        if (entry.id() < 1) {
            throw fail("entry " + index + ": field 'id' must be a positive integer, got " + entry.id());
        }
        String where = "entry " + index + " (id " + entry.id() + ")";
        String name = requireText(entry.name(), "name", where);
        Tier tier = parseTier(requireText(entry.tier(), "tier", where), where);
        return new Tenant(entry.id(), name, tier);
    }

    private String requireText(String value, String field, String where) {
        if (value == null || value.isBlank()) {
            throw fail(where + ": field '" + field + "' is missing or blank");
        }
        return value;
    }

    private Tier parseTier(String value, String where) {
        try {
            return Tier.parse(value);
        } catch (IllegalArgumentException e) {
            throw fail(where + ": " + e.getMessage(), e);
        }
    }

    private TenantLoadException fail(String problem) {
        return new TenantLoadException(description(), problem);
    }

    private TenantLoadException fail(String problem, Throwable cause) {
        return new TenantLoadException(description(), problem, cause);
    }
}
