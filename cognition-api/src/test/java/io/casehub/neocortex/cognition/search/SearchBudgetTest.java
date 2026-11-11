package io.casehub.neocortex.cognition.search;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SearchBudgetTest {

    @Test
    void valid_budget_accepted() {
        var budget = new SearchBudget(0.5, 0.3, 0.7, 0.4, SearchMotivation.CURIOSITY, false, false, 0.0, 0.0, 0.0, -1.0, -1.0, -1.0, Instant.now());
        assertEquals(0.5, budget.breadth());
        assertEquals(0.3, budget.laterality());
        assertEquals(0.7, budget.depth());
        assertEquals(0.4, budget.convergence());
        assertEquals(SearchMotivation.CURIOSITY, budget.source());
    }

    @Test
    void rejects_breadth_above_one() {
        assertThrows(IllegalArgumentException.class, () ->
                new SearchBudget(1.1, 0.3, 0.7, 0.4, SearchMotivation.CURIOSITY, false, false, 0.0, 0.0, 0.0, -1.0, -1.0, -1.0, Instant.now()));
    }

    @Test
    void rejects_negative_convergence() {
        assertThrows(IllegalArgumentException.class, () ->
                new SearchBudget(0.5, 0.3, 0.7, -0.1, SearchMotivation.COMPETENCE, false, false, 0.0, 0.0, 0.0, -1.0, -1.0, -1.0, Instant.now()));
    }

    @Test
    void rejects_null_source() {
        assertThrows(NullPointerException.class, () ->
                new SearchBudget(0.5, 0.3, 0.7, 0.4, null, false, false, 0.0, 0.0, 0.0, -1.0, -1.0, -1.0, Instant.now()));
    }

    @Test
    void rejects_null_evaluated_at() {
        assertThrows(NullPointerException.class, () ->
                new SearchBudget(0.5, 0.3, 0.7, 0.4, SearchMotivation.CURIOSITY, false, false, 0.0, 0.0, 0.0, -1.0, -1.0, -1.0, null));
    }

    @Test
    void all_motivation_values_present() {
        assertEquals(6, SearchMotivation.values().length);
    }

    @Test
    void from_drive_axis_maps_all_axes() {
        assertEquals(SearchMotivation.CURIOSITY,
                SearchMotivation.fromDriveAxis(io.casehub.neocortex.cognition.drive.DriveAxis.CURIOSITY));
        assertEquals(SearchMotivation.COMPETENCE,
                SearchMotivation.fromDriveAxis(io.casehub.neocortex.cognition.drive.DriveAxis.COMPETENCE));
        assertEquals(SearchMotivation.AFFILIATION,
                SearchMotivation.fromDriveAxis(io.casehub.neocortex.cognition.drive.DriveAxis.AFFILIATION));
        assertEquals(SearchMotivation.AUTONOMY,
                SearchMotivation.fromDriveAxis(io.casehub.neocortex.cognition.drive.DriveAxis.AUTONOMY));
    }
}
