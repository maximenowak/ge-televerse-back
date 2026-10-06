package ch.ge.ael.geteleverse;

import ch.ge.ael.geteleverse.config.AntivirusProperties;
import ch.ge.ael.geteleverse.config.AppSecurityProperties;
import ch.ge.ael.geteleverse.config.AwsProperties;
import ch.ge.ael.geteleverse.config.CorsProperties;
import ch.ge.ael.geteleverse.config.EditAdmissionProperties;
import ch.ge.ael.geteleverse.config.FinalizeAdmissionProperties;
import ch.ge.ael.geteleverse.config.GhostscriptProperties;
import ch.ge.ael.geteleverse.config.ImageProcessingProperties;
import ch.ge.ael.geteleverse.config.LimitsProperties;
import ch.ge.ael.geteleverse.config.MergeAdmissionProperties;
import ch.ge.ael.geteleverse.config.OriginatorProperties;
import ch.ge.ael.geteleverse.config.SameOriginProperties;
import ch.ge.ael.geteleverse.config.SessionCreateProperties;
import ch.ge.ael.geteleverse.config.SessionRateLimitProperties;
import ch.ge.ael.geteleverse.config.ThumbnailCacheProperties;
import ch.ge.ael.geteleverse.config.UploadAdmissionProperties;
import ch.ge.ael.geteleverse.config.UploadFormatProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({AwsProperties.class, LimitsProperties.class, GhostscriptProperties.class,
        UploadAdmissionProperties.class, ImageProcessingProperties.class, OriginatorProperties.class,
        CorsProperties.class, AppSecurityProperties.class, AntivirusProperties.class,
        UploadFormatProperties.class, SessionRateLimitProperties.class, SameOriginProperties.class,
        SessionCreateProperties.class, EditAdmissionProperties.class,
        MergeAdmissionProperties.class, FinalizeAdmissionProperties.class,
        ThumbnailCacheProperties.class})
public class GeTeleverseApplication {

    public static void main(String[] args) {
        SpringApplication.run(GeTeleverseApplication.class, args);
    }
}
