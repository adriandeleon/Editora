package com.editora.github;

import java.util.List;

import com.editora.github.PrReviewArgs.ReviewAction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrReviewArgsTest {

    @Test
    void approveOmitsBodyWhenBlank() {
        assertEquals(List.of("pr", "review", "42", "--approve"), PrReviewArgs.build(42, ReviewAction.APPROVE, ""));
        assertEquals(List.of("pr", "review", "42", "--approve"), PrReviewArgs.build(42, ReviewAction.APPROVE, null));
    }

    @Test
    void approveIncludesBodyWhenPresent() {
        assertEquals(
                List.of("pr", "review", "42", "--approve", "--body", "LGTM"),
                PrReviewArgs.build(42, ReviewAction.APPROVE, "LGTM"));
    }

    @Test
    void requestChangesAndCommentAlwaysPassBody() {
        assertEquals(
                List.of("pr", "review", "7", "--request-changes", "--body", "fix this"),
                PrReviewArgs.build(7, ReviewAction.REQUEST_CHANGES, "fix this"));
        assertEquals(
                List.of("pr", "review", "7", "--comment", "--body", "note"),
                PrReviewArgs.build(7, ReviewAction.COMMENT, "note"));
    }

    @Test
    void bodyRequiredExceptForApprove() {
        assertFalse(PrReviewArgs.bodyRequired(ReviewAction.APPROVE));
        assertTrue(PrReviewArgs.bodyRequired(ReviewAction.REQUEST_CHANGES));
        assertTrue(PrReviewArgs.bodyRequired(ReviewAction.COMMENT));
    }
}
