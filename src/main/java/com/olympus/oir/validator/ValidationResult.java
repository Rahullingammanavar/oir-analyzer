package com.olympus.oir.validator;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Holds the XSD validation result for a single XML section of an OIR file.
 *
 * Three possible states:
 *  VALID   — XML is well-formed and passes schema check.
 *  WARNING — XML parses but has non-fatal schema deviations.
 *  ERROR   — XML is malformed or fails critical schema rules.
 */
public class ValidationResult {

    public enum Status { VALID, WARNING, ERROR }

    private final String     sectionName;
    private final int        sectionId;
    private final Status     status;
    private final List<String> messages;   // errors / warnings (empty when VALID)
    private final Instant    checkedAt;

    // ── Factory methods ───────────────────────────────────────────────────────

    public static ValidationResult valid(String sectionName, int sectionId) {
        return new ValidationResult(sectionName, sectionId, Status.VALID, List.of());
    }

    public static ValidationResult warning(String sectionName, int sectionId, List<String> msgs) {
        return new ValidationResult(sectionName, sectionId, Status.WARNING, msgs);
    }

    public static ValidationResult error(String sectionName, int sectionId, List<String> msgs) {
        return new ValidationResult(sectionName, sectionId, Status.ERROR, msgs);
    }

    // ── Constructor ───────────────────────────────────────────────────────────

    private ValidationResult(String sectionName, int sectionId, Status status, List<String> messages) {
        this.sectionName = sectionName;
        this.sectionId   = sectionId;
        this.status      = status;
        this.messages    = Collections.unmodifiableList(messages);
        this.checkedAt   = Instant.now();
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String      getSectionName() { return sectionName; }
    public int         getSectionId()   { return sectionId; }
    public Status      getStatus()      { return status; }
    public List<String> getMessages()   { return messages; }
    public Instant     getCheckedAt()   { return checkedAt; }

    /** Convenience: true only when status == VALID. */
    public boolean isValid() { return status == Status.VALID; }

    /**
     * Returns a status emoji for UI display.
     * ✅ VALID | ⚠ WARNING | ❌ ERROR
     */
    public String getStatusEmoji() {
        return switch (status) {
            case VALID   -> "✅";
            case WARNING -> "⚠";
            case ERROR   -> "❌";
        };
    }

    /**
     * Returns a short one-line summary (emoji + section name + first message if any).
     * Example: "✅ FILE_INFORMATION"
     * Example: "❌ IMAGE_ANNOTATION — Parse/schema error: ..."
     */
    public String summary() {
        if (messages.isEmpty()) {
            return getStatusEmoji() + "  " + sectionName;
        }
        return getStatusEmoji() + "  " + sectionName + "  —  " + messages.get(0);
    }

    @Override
    public String toString() {
        return "ValidationResult{section='" + sectionName + "', status=" + status
               + ", messages=" + messages.size() + "}";
    }
}
