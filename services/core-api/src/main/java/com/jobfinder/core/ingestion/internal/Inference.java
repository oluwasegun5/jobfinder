package com.jobfinder.core.ingestion.internal;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.jobfinder.core.ingestion.internal.NormalizedJob.EmploymentType;
import com.jobfinder.core.ingestion.internal.NormalizedJob.Seniority;
import com.jobfinder.core.ingestion.internal.NormalizedJob.WorkMode;

/**
 * Rules that read work mode, employment type and seniority from the words a posting uses. Each returns
 * null when nothing in the text says: an unknown is stored as an unknown, never as a default guess,
 * except where the rule below says otherwise.
 */
final class Inference {

    private static final Pattern TITLE_HYBRID = Pattern.compile("\\bhybrid\\b");
    private static final Pattern TITLE_REMOTE = Pattern.compile("\\b(remote(ly)?|work from home|wfh)\\b");
    private static final Pattern TITLE_ONSITE = Pattern.compile("\\b(on[- ]?site|in[- ]office|office[- ]based)\\b");
    private static final Pattern DESC_HYBRID = Pattern.compile(
            "\\bhybrid (work|working|role|model|position|schedule|environment|setup|set-up|arrangement|basis|policy)\\b"
                    + "|\\b(this|the) (role|position|job) is hybrid\\b|\\b\\d days? (a|per) week (in|at) the office\\b");
    private static final Pattern DESC_REMOTE = Pattern.compile(
            "\\b(fully|100%|entirely|completely) remote\\b|\\bremote[- ]first\\b|\\bwork from anywhere\\b"
                    + "|\\b(this|the) (is a )?(role|position|job|opportunity) is (fully |100% )?remote\\b"
                    + "|\\bthis is a (fully )?remote (role|position|job|opportunity)\\b");
    private static final Pattern DESC_ONSITE = Pattern.compile(
            "\\b(this|the) (role|position|job) is (fully )?(on[- ]?site|office[- ]based)\\b"
                    + "|\\bthis is an? (on[- ]?site|office[- ]based) (role|position|job)\\b");

    private static final Pattern TITLE_INTERN = Pattern.compile(
            "\\b(intern|interns|internship|trainee|apprentice|apprenticeship|working student|werkstudent|co op)\\b");
    private static final Pattern TITLE_EXECUTIVE = Pattern.compile(
            "\\b(chief|cto|ceo|cfo|coo|cio|ciso|cpo|cmo|vp|svp|evp|vice president|director|head of|managing director"
                    + "|president)\\b");
    private static final Pattern TITLE_LEAD = Pattern.compile(
            "\\b(principal|distinguished|lead(?! gen| generation| qualification)|team lead|tech lead"
                    + "|staff (\\w+ ){0,3}(engineer|developer|scientist|designer|researcher|architect|programmer|swe|sre)"
                    + "|senior manager|manager of"
                    + "|(engineering|development|delivery|team|people|department|group|general|operations|regional|area"
                    + "|branch|store|sales|support|it|technical) manager)\\b");
    private static final Pattern TITLE_SENIOR = Pattern.compile(
            "\\b(senior|sr|snr|iii|level 3|mid senior)\\b");
    private static final Pattern TITLE_MID = Pattern.compile("\\b(mid|mid level|midlevel|intermediate|ii|level 2)\\b");
    private static final Pattern TITLE_JUNIOR = Pattern.compile(
            "\\b(junior|jr|entry level|graduate|grad|new grad|early career|level 1)\\b");

    private static final Pattern DESC_EMPLOYMENT = Pattern.compile(
            "(?:employment|job|position|contract) type\\s*[:\\-]\\s*([a-z][a-z \\-/_]{2,24})");

    private Inference() {
    }

    /**
     * Remote, hybrid or on-site. The source's explicit flag and the location and title come first; the
     * description is read only for unmistakable statements ("fully remote", "this role is hybrid"),
     * because "remote teams" and "onsite gym" appear in postings of every kind. A posting with a place
     * and no remote or hybrid signal is on-site; one with no place and no signal is unknown.
     */
    static WorkMode workMode(Boolean remoteFlag, LocationParser.Parsed location, String title, String description) {
        String shortText = title == null ? "" : title.toLowerCase(Locale.ROOT);
        String desc = description == null ? "" : description.toLowerCase(Locale.ROOT);

        if (location.hybrid() || TITLE_HYBRID.matcher(shortText).find()) {
            return WorkMode.HYBRID;
        }
        if (Boolean.TRUE.equals(remoteFlag) || location.remote() || TITLE_REMOTE.matcher(shortText).find()) {
            return WorkMode.REMOTE;
        }
        if (DESC_HYBRID.matcher(desc).find()) {
            return WorkMode.HYBRID;
        }
        if (DESC_REMOTE.matcher(desc).find()) {
            return WorkMode.REMOTE;
        }
        if (location.onsite() || TITLE_ONSITE.matcher(shortText).find() || DESC_ONSITE.matcher(desc).find()
                || Boolean.FALSE.equals(remoteFlag)) {
            return WorkMode.ONSITE;
        }
        if (location.city() != null || location.country() != null) {
            return WorkMode.ONSITE;
        }
        return null;
    }

    /** From the source's label first, then the title, then an explicit "Employment type: ..." line. */
    static EmploymentType employmentType(String label, String title, String description) {
        EmploymentType fromLabel = fromWords(label);
        if (fromLabel != null) {
            return fromLabel;
        }
        String shortText = title == null ? "" : title.toLowerCase(Locale.ROOT);
        if (TITLE_INTERN.matcher(shortText.replaceAll("[^a-z0-9]+", " ")).find()) {
            return EmploymentType.INTERNSHIP;
        }
        if (shortText.matches(".*\\bpart[- ]?time\\b.*")) {
            return EmploymentType.PART_TIME;
        }
        if (shortText.matches(".*\\b(contract|contractor|freelance|fixed[- ]term)\\b.*")) {
            return EmploymentType.CONTRACT;
        }
        if (shortText.matches(".*\\b(temporary|temp|seasonal)\\b.*")) {
            return EmploymentType.TEMPORARY;
        }
        if (description != null) {
            Matcher matcher = DESC_EMPLOYMENT.matcher(description.toLowerCase(Locale.ROOT));
            if (matcher.find()) {
                return fromWords(matcher.group(1));
            }
        }
        return null;
    }

    private static EmploymentType fromWords(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", " ").trim();
        if (text.contains("intern") || text.contains("apprentice") || text.contains("trainee")) {
            return EmploymentType.INTERNSHIP;
        }
        if (text.contains("part time") || text.contains("parttime")) {
            return EmploymentType.PART_TIME;
        }
        if (text.contains("contract") || text.contains("freelance") || text.contains("consult")
                || text.contains("fixed term")) {
            return EmploymentType.CONTRACT;
        }
        if (text.contains("temp") || text.contains("seasonal") || text.contains("casual")) {
            return EmploymentType.TEMPORARY;
        }
        if (text.contains("full time") || text.contains("fulltime") || text.contains("permanent")
                || text.contains("regular")) {
            return EmploymentType.FULL_TIME;
        }
        return null;
    }

    /**
     * From the title alone: the description of a senior role mentions juniors and the other way round.
     * The strongest word wins (an "Intern" before a "Director", a "Lead" before a "Senior"). Generic
     * "manager" titles (product, project, account, program) say nothing about seniority: those managers
     * are individual contributors, so only managers of engineering, teams, people and the like count.
     */
    static Seniority seniority(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String text = TextCleaner.fold(title).replaceAll("[^a-z0-9,]+", " ").replaceAll(" ,", ",").trim();
        if (TITLE_INTERN.matcher(text).find()) {
            return Seniority.INTERN;
        }
        if (TITLE_EXECUTIVE.matcher(text).find()) {
            return Seniority.EXECUTIVE;
        }
        if (TITLE_LEAD.matcher(text).find()) {
            return Seniority.LEAD;
        }
        if (TITLE_SENIOR.matcher(text).find()) {
            return Seniority.SENIOR;
        }
        if (TITLE_MID.matcher(text).find()) {
            return Seniority.MID;
        }
        if (TITLE_JUNIOR.matcher(text).find()) {
            return Seniority.JUNIOR;
        }
        return null;
    }
}
