package ch.ge.ael.geteleverse.config;

import ch.ge.ael.geteleverse.model.SizeProfile;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.util.EnumMap;
import java.util.Map;

@ConfigurationProperties(prefix = "app.limits")
public record LimitsProperties(
        int maxPages,
        DataSize multipartCeiling,
        Map<SizeProfile, Profile> profiles
) {
    public record Profile(DataSize maxTotalSize) {}

    public Profile profileFor(SizeProfile sizeProfile) {
        var key = sizeProfile != null ? sizeProfile : SizeProfile.DEFAULT;
        var profile = profiles != null ? profiles.get(key) : null;
        if (profile == null) {
            // Fallback : defensive default if config is missing
            profile = profiles != null ? profiles.get(SizeProfile.DEFAULT) : null;
        }
        if (profile == null) {
            throw new IllegalStateException("No limits profile configured for " + key);
        }
        return profile;
    }

    public long maxTotalBytes(SizeProfile sizeProfile) {
        return profileFor(sizeProfile).maxTotalSize().toBytes();
    }

    /**
     * Normalise la map en EnumMap pour garantir le lookup par enum après le binding Spring.
     */
    public LimitsProperties {
        if (profiles != null && !(profiles instanceof EnumMap)) {
            var normalized = new EnumMap<SizeProfile, Profile>(SizeProfile.class);
            normalized.putAll(profiles);
            profiles = normalized;
        }
    }
}
