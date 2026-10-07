package com.jobfinder.core.interview.internal;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.identity.UserDataBundle;
import com.jobfinder.core.identity.UserDataExporter;
import com.jobfinder.core.shared.UserDataJson;

/** Interview's share of the data export: prep questions, company briefs, mock sessions with every answer and feedback. */
@Component
class InterviewDataExporter implements UserDataExporter {

    private final JdbcClient jdbc;

    InterviewDataExporter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String module() {
        return "interview";
    }

    @Override
    public void export(UUID userId, UserDataBundle bundle) {
        bundle.json("prep", UserDataJson.rows(jdbc,
                "select t.* from interview_prep t where t.user_id = :userId order by t.created_at, t.id", userId));
        bundle.json("prep-questions", UserDataJson.rows(jdbc, """
                select q.* from interview_questions q join interview_prep p on p.id = q.prep_id
                 where p.user_id = :userId order by p.created_at, p.id, q.position
                """, userId));
        bundle.json("company-briefs", UserDataJson.rows(jdbc, """
                select b.* from company_briefs b join interview_prep p on p.id = b.prep_id
                 where p.user_id = :userId order by p.created_at, p.id
                """, userId));
        bundle.json("mock-sessions", UserDataJson.rows(jdbc,
                "select t.* from interview_sessions t where t.user_id = :userId order by t.created_at, t.id", userId));
        bundle.json("mock-turns", UserDataJson.rows(jdbc, """
                select t.* from interview_turns t join interview_sessions s on s.id = t.session_id
                 where s.user_id = :userId order by s.created_at, s.id, t.position
                """, userId));
    }
}
