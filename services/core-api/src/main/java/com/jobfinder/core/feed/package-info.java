/**
 * The "For you" feed: the user's matches (from {@code matching}, read from the score cache) adjusted by what they
 * saved, hid and applied to (read from {@code jobs}), cursor-paged. It owns no tables and has no public API; see
 * docs/adr/0027-feed-and-feedback.md.
 */
package com.jobfinder.core.feed;
