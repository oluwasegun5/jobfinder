package com.jobfinder.core.feed.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jobfinder.core.feed.internal.FeedDtos.FeedPage;
import com.jobfinder.core.identity.CurrentUser;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** The caller's "For you" feed (docs/adr/0027-feed-and-feedback.md). The user is the token's user, never a parameter. */
@RestController
class FeedController {

    private final FeedService feed;

    FeedController(FeedService feed) {
        this.feed = feed;
    }

    /**
     * The caller's matched jobs, best first, with the score, where it came from, strengths and gaps, and the points
     * their saves, hides and applications added or took away (with the reasons). Hidden and applied jobs are not in
     * it. Pages are keyset-paged: pass {@code nextCursor} back as {@code cursor}. When the first page is empty,
     * {@code emptyReason} says why (no resume, no preferences, resume still processing, no matches).
     */
    @GetMapping("/feed")
    FeedPage feed(@RequestParam(required = false) @Min(1) @Max(FeedDtos.MAX_LIMIT) Integer limit,
            @RequestParam(required = false) @Size(max = 600) String cursor) {
        return feed.page(CurrentUser.require().id(), limit, cursor);
    }
}
