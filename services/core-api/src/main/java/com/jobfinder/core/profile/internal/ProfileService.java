package com.jobfinder.core.profile.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.jobfinder.core.profile.internal.ProfileDtos.ProfileRequest;
import com.jobfinder.core.profile.internal.ProfileDtos.ProfileResponse;
import com.jobfinder.core.profile.internal.ProfileDtos.Seniority;
import com.jobfinder.core.profile.internal.ResumeContentDtos.Link;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The caller's single profile row. Both methods take the user ID from the access token (never from a request) and
 * every statement is keyed by it, so there is no way to address another user's profile. A user who has never
 * saved anything gets an empty profile back rather than a 404.
 */
@Service
class ProfileService {

    private static final TypeReference<List<Link>> LINKS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;

    ProfileService(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    ProfileResponse get(UUID userId) {
        boolean onboarded = PreferencesService.exists(jdbc, userId);
        return jdbc.sql("""
                select full_name, headline, location, phone, links::text as links, years_experience, seniority,
                       updated_at
                from profiles where user_id = :userId
                """).param("userId", userId)
                .query((rs, row) -> new ProfileResponse(rs.getString("full_name"), rs.getString("headline"),
                        rs.getString("location"), rs.getString("phone"), links(rs.getString("links")),
                        (Integer) rs.getObject("years_experience"),
                        rs.getString("seniority") == null ? null : Seniority.valueOf(rs.getString("seniority")),
                        onboarded, rs.getTimestamp("updated_at").toInstant()))
                .optional()
                .orElseGet(() -> new ProfileResponse(null, null, null, null, List.of(), null, null, onboarded, null));
    }

    ProfileResponse save(UUID userId, ProfileRequest request) {
        jdbc.sql("""
                insert into profiles (id, user_id, full_name, headline, location, phone, links, years_experience,
                                      seniority, created_at, updated_at)
                values (:id, :userId, :fullName, :headline, :location, :phone, cast(:links as jsonb),
                        :yearsExperience, :seniority, now(), now())
                on conflict (user_id) do update set
                    full_name = excluded.full_name, headline = excluded.headline, location = excluded.location,
                    phone = excluded.phone, links = excluded.links, years_experience = excluded.years_experience,
                    seniority = excluded.seniority, updated_at = now()
                """)
                .param("id", UUID.randomUUID())
                .param("userId", userId)
                .param("fullName", request.fullName())
                .param("headline", request.headline())
                .param("location", request.location())
                .param("phone", request.phone())
                .param("links", writeLinks(request.links()))
                .param("yearsExperience", request.yearsExperience(), java.sql.Types.INTEGER)
                .param("seniority", request.seniority() == null ? null : request.seniority().name(),
                        java.sql.Types.VARCHAR)
                .update();
        return get(userId);
    }

    private String writeLinks(List<Link> links) {
        try {
            return json.writeValueAsString(links);
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not serialise links", e);
        }
    }

    private List<Link> links(String stored) {
        try {
            return stored == null ? List.of() : json.readValue(stored, LINKS);
        } catch (JacksonException e) {
            return List.of();
        }
    }
}
