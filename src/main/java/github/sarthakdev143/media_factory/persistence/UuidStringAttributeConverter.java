package github.sarthakdev143.media_factory.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.UUID;

@Converter(autoApply = true)
public class UuidStringAttributeConverter implements AttributeConverter<UUID, String> {

    @Override
    public String convertToDatabaseColumn(UUID attribute) {
        return attribute == null ? null : attribute.toString();
    }

    @Override
    public UUID convertToEntityAttribute(String dbData) {
        return dbData == null || dbData.isBlank() ? null : UUID.fromString(dbData);
    }
}
