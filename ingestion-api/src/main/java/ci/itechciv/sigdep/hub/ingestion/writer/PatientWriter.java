package ci.itechciv.sigdep.hub.ingestion.writer;

import ci.itechciv.sigdep.contracts.SyncBatchResponse;
import ci.itechciv.sigdep.contracts.SyncBatchResponse.RecordError;
import ci.itechciv.sigdep.contracts.dto.PatientDto;
import ci.itechciv.sigdep.contracts.dto.PatientDto.IdentifierDto;
import ci.itechciv.sigdep.hub.domain.repository.IdentifierTypeRepository;
import java.sql.Date;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class PatientWriter {

    private static final Logger log = LoggerFactory.getLogger(PatientWriter.class);

    private final JdbcTemplate jdbc;
    private final IdentifierTypeRepository identifierTypes;
    private final Map<String, Integer> identifierTypeIdByCode = new HashMap<>();
    private final TransactionTemplate tx;

    public PatientWriter(JdbcTemplate jdbc,
                         IdentifierTypeRepository identifierTypes,
                         PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.identifierTypes = identifierTypes;
        this.tx = new TransactionTemplate(txManager);
    }

    public BatchResult upsertBatch(long siteId, List<PatientDto> batch) {
        int accepted = 0;
        int rejected = 0;
        List<RecordError> errors = new ArrayList<>();

        for (PatientDto p : batch) {
            try {
                tx.executeWithoutResult(status -> {
                    long patientId = upsertPatient(siteId, p);
                    upsertIdentifiers(siteId, patientId, p.identifiers());
                });
                accepted++;
            } catch (RuntimeException e) {
                rejected++;
                errors.add(new RecordError(
                        p.sourceUuid(),
                        "UPSERT_FAILED",
                        e.getClass().getSimpleName() + ": " + e.getMessage()));
                log.warn("Failed to upsert patient {}: {}", p.sourceUuid(), e.getMessage());
            }
        }
        return new BatchResult(accepted, rejected, errors);
    }

    private long upsertPatient(long siteId, PatientDto p) {
        return jdbc.queryForObject(
                """
                INSERT INTO core.patients (
                  site_id, source_uuid, sex, birth_date, birth_date_estimated,
                  birth_place, profession, education_level, marital_status, religion,
                  voided, voided_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NOW(), NOW())
                ON CONFLICT (site_id, source_uuid) DO UPDATE SET
                  sex                  = EXCLUDED.sex,
                  birth_date           = EXCLUDED.birth_date,
                  birth_date_estimated = EXCLUDED.birth_date_estimated,
                  birth_place          = COALESCE(EXCLUDED.birth_place,     core.patients.birth_place),
                  profession           = COALESCE(EXCLUDED.profession,      core.patients.profession),
                  education_level      = COALESCE(EXCLUDED.education_level, core.patients.education_level),
                  marital_status       = COALESCE(EXCLUDED.marital_status,  core.patients.marital_status),
                  religion             = COALESCE(EXCLUDED.religion,        core.patients.religion),
                  voided               = EXCLUDED.voided,
                  voided_at            = CASE WHEN EXCLUDED.voided = TRUE THEN NOW() ELSE NULL END,
                  updated_at           = NOW()
                RETURNING id
                """,
                Long.class,
                siteId,
                p.sourceUuid(),
                p.sex(),
                p.birthDate() == null ? null : Date.valueOf(p.birthDate()),
                p.birthDateEstimated(),
                p.birthPlace(),
                p.profession(),
                p.educationLevel(),
                p.maritalStatus(),
                p.religion(),
                Boolean.TRUE.equals(p.voided()));
    }

    private void upsertIdentifiers(long siteId, long patientId, List<IdentifierDto> identifiers) {
        if (identifiers == null || identifiers.isEmpty()) {
            return;
        }
        for (IdentifierDto id : identifiers) {
            Integer typeId = identifierTypeId(id.typeCode());
            if (typeId == null) {
                log.debug("Unknown identifier type code '{}', skipped", id.typeCode());
                continue;
            }
            jdbc.update(
                    """
                    INSERT INTO core.patient_identifiers (
                      patient_id, identifier_type_id, identifier_value,
                      is_preferred, site_id, valid_from, valid_to,
                      voided, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, FALSE, NOW(), NOW())
                    ON CONFLICT (identifier_type_id, identifier_value, site_id) DO UPDATE SET
                      is_preferred = EXCLUDED.is_preferred,
                      valid_from   = EXCLUDED.valid_from,
                      valid_to     = EXCLUDED.valid_to,
                      updated_at   = NOW()
                    """,
                    new Object[] {
                            patientId,
                            typeId,
                            id.value(),
                            Boolean.TRUE.equals(id.preferred()),
                            siteId,
                            id.validFrom() == null ? null : Date.valueOf(id.validFrom()),
                            id.validTo() == null ? null : Date.valueOf(id.validTo()),
                    },
                    new int[] {
                            Types.BIGINT, Types.INTEGER, Types.VARCHAR, Types.BOOLEAN,
                            Types.BIGINT, Types.DATE, Types.DATE
                    });
        }
    }

    private Integer identifierTypeId(String code) {
        if (code == null) return null;
        return identifierTypeIdByCode.computeIfAbsent(code, c ->
                identifierTypes.findByCode(c).map(t -> t.getId()).orElse(null));
    }

    public record BatchResult(int accepted, int rejected, List<SyncBatchResponse.RecordError> errors) {}
}
