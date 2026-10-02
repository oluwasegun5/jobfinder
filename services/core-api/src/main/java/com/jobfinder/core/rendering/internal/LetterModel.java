package com.jobfinder.core.rendering.internal;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * A cover letter reduced to what is printed, as clean strings, in the order it is printed: the sender (name and
 * contact lines), the recipient (company and the job applied for), the salutation, the paragraphs, the closing and the
 * signature. Both renderers read this and nothing else (the same rule as {@link ResumeModel}).
 *
 * <p>The input is the content of an approved COVER_LETTER document (docs/adr/0031-cover-letters-and-application-pack.md).
 * There is no date line: a rendered file is a pure function of the approved text (ADR 0030), and a date of rendering
 * would make the same letter a different file every day. The user adds a date where they send it.
 */
record LetterModel(String name, List<String> contactLines, List<String> recipientLines, String salutation,
        List<String> paragraphs, String closing, String signature) implements Printable {

    @Override
    public String fileKind() {
        return "Cover_Letter";
    }

    static LetterModel parse(JsonNode root) {
        JsonNode sender = root.path("sender");
        List<String> plain = new ArrayList<>();
        for (String field : List.of("email", "phone", "location")) {
            String value = ResumeModel.text(sender, field);
            if (!value.isEmpty()) {
                plain.add(value);
            }
        }
        List<String> links = new ArrayList<>();
        for (JsonNode link : ResumeModel.array(sender, "links")) {
            String url = ResumeModel.text(link, "url");
            String label = ResumeModel.text(link, "label");
            if (!url.isEmpty()) {
                links.add(label.isEmpty() ? url : label + ": " + url);
            }
        }
        List<String> contact = new ArrayList<>();
        if (!plain.isEmpty()) {
            contact.add(String.join(" | ", plain));
        }
        if (!links.isEmpty()) {
            contact.add(String.join(" | ", links));
        }
        JsonNode recipient = root.path("recipient");
        List<String> to = new ArrayList<>();
        String company = ResumeModel.text(recipient, "company");
        if (!company.isEmpty()) {
            to.add(company);
        }
        String job = ResumeModel.text(recipient, "job_title");
        if (!job.isEmpty()) {
            to.add("Re: Application for " + job);
        }
        List<String> paragraphs = new ArrayList<>();
        for (JsonNode p : ResumeModel.array(root, "paragraphs")) {
            String cleaned = p.isString() ? ResumeModel.clean(p.asString()) : "";
            if (!cleaned.isEmpty()) {
                paragraphs.add(cleaned);
            }
        }
        String name = ResumeModel.text(sender, "full_name");
        String signature = ResumeModel.text(root, "signature");
        return new LetterModel(name, List.copyOf(contact), List.copyOf(to), ResumeModel.text(root, "salutation"),
                List.copyOf(paragraphs), ResumeModel.text(root, "closing"), signature.isEmpty() ? name : signature);
    }
}
