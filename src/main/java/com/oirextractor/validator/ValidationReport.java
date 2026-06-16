package com.oirextractor.validator;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Model class containing the results of XML validation against XSDs.
 */
public class ValidationReport {

    private String sourceFileName;
    private LocalDate validationDate;
    private List<ValidationResult> results;

    public ValidationReport(String sourceFileName) {
        this.sourceFileName = sourceFileName;
        this.validationDate = LocalDate.now();
        this.results = new ArrayList<>();
    }

    public void addResult(ValidationResult result) {
        this.results.add(result);
    }

    public List<ValidationResult> getResults() {
        return results;
    }

    public int getTotalSections() {
        return results.size();
    }

    public int getPassedSections() {
        return (int) results.stream().filter(ValidationResult::isPass).count();
    }

    public int getFailedSections() {
        return (int) results.stream().filter(r -> !r.isPass()).count();
    }

    public String getOverallStatus() {
        int failed = getFailedSections();
        if (results.isEmpty()) return "❓ NO SECTIONS VALIDATED";
        if (failed == 0) return "✅ FULL PASS";
        if (failed < results.size()) return "⚠️ PARTIAL PASS";
        return "❌ FULL FAIL";
    }

    /**
     * Writes the validation report to a text file using the required format.
     */
    public void writeReport(File outputFile) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
            writer.println("════════════════════════════════════");
            writer.println("OIR XML VALIDATION REPORT");
            writer.println("════════════════════════════════════");
            writer.println("File    : " + sourceFileName);
            writer.println("Date    : " + validationDate);
            writer.println("────────────────────────────────────");
            writer.println(String.format("%-20s %-7s %s", "Section", "Status", "Detail"));
            writer.println("────────────────────────────────────");
            
            for (ValidationResult res : results) {
                String statusStr = res.isPass() ? "✅ Pass" : "❌ Fail";
                String detail = res.isPass() ? "" : res.getErrorMessage();
                writer.println(String.format("%-20s %-7s %s", res.getSectionName(), statusStr, detail));
            }
            
            writer.println("────────────────────────────────────");
            writer.println(String.format("Total  : %d", getTotalSections()));
            writer.println(String.format("Passed : %d", getPassedSections()));
            writer.println(String.format("Failed : %2d", getFailedSections()));
            writer.println("Status : " + getOverallStatus());
            writer.println("════════════════════════════════════");
        }
    }

    /**
     * Inner class representing the validation result of a single section.
     */
    public static class ValidationResult {
        private String sectionName;
        private boolean pass;
        private String errorMessage;

        public ValidationResult(String sectionName, boolean pass, String errorMessage) {
            this.sectionName = sectionName;
            this.pass = pass;
            this.errorMessage = errorMessage != null ? errorMessage.trim() : "";
        }

        public String getSectionName() { return sectionName; }
        public boolean isPass() { return pass; }
        public String getErrorMessage() { return errorMessage; }
    }
}
