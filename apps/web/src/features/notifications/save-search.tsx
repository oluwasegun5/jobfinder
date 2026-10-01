"use client";

import { BellPlus } from "lucide-react";
import Link from "next/link";
import { useState, type FormEvent } from "react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { problemMessage } from "@/features/auth/api-errors";
import { FormError, FormNotice } from "@/features/auth/form-parts";
import type { JobFilters } from "@/features/jobs/search-params";
import { ApiProblem } from "@/features/profile/queries";

import { criteriaAreEmpty, describeCriteria, filtersToCriteria, FREQUENCIES, FREQUENCY_LABELS } from "./criteria";
import { useCreateSavedSearch, type SavedSearchFrequency } from "./queries";

/** "Save this search" on the jobs page: keeps the current keyword and filters and chooses how often to be emailed. */
export function SaveSearch({ filters }: { filters: JobFilters }) {
  const criteria = filtersToCriteria(filters);
  const empty = criteriaAreEmpty(criteria);
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [frequency, setFrequency] = useState<SavedSearchFrequency>("DAILY");
  const [savedName, setSavedName] = useState<string>();
  const create = useCreateSavedSearch();

  const summary = describeCriteria(criteria);
  const problem = create.error instanceof ApiProblem ? create.error.problem : undefined;

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    const finalName = (name.trim() || summary).slice(0, 100);
    try {
      await create.mutateAsync({ name: finalName, criteria, frequency });
    } catch {
      return;
    }
    setSavedName(finalName);
    setOpen(false);
    setName("");
  }

  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2">
        <Button
          variant="outline"
          size="sm"
          aria-expanded={open}
          aria-controls="save-search-form"
          disabled={empty}
          onClick={() => {
            setSavedName(undefined);
            create.reset();
            setOpen((o) => !o);
          }}
        >
          <BellPlus /> Save this search
        </Button>
        {empty && <span className="text-xs text-muted-foreground">Add a keyword or a filter to save a search.</span>}
      </div>
      {savedName && (
        <FormNotice>
          Saved &quot;{savedName}&quot;.{" "}
          <Link href="/saved-searches" className="underline">
            Manage saved searches
          </Link>
        </FormNotice>
      )}
      {open && (
        <form id="save-search-form" aria-label="Save this search" className="flex flex-col gap-3 rounded-xl border p-4" onSubmit={onSubmit}>
          <p className="text-sm text-muted-foreground">{summary}</p>
          {(filters.postedWithinDays || filters.companyId) && (
            <p className="text-xs text-muted-foreground">
              The posting window and the company filter are not saved: a saved search is about new jobs.
            </p>
          )}
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="saved-search-name">Name</Label>
            <Input
              id="saved-search-name"
              maxLength={100}
              placeholder={summary.slice(0, 100)}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="saved-search-frequency">Email me</Label>
            <select
              id="saved-search-frequency"
              className="h-9 w-full min-w-0 rounded-lg border border-input bg-transparent px-2 text-base outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 md:text-sm dark:bg-input/30"
              value={frequency}
              onChange={(e) => setFrequency(e.target.value as SavedSearchFrequency)}
            >
              {FREQUENCIES.map((f) => (
                <option key={f} value={f}>
                  {FREQUENCY_LABELS[f]}
                </option>
              ))}
            </select>
          </div>
          <FormError>{create.isError ? problemMessage(problem, "We couldn't save this search. Please try again.") : undefined}</FormError>
          <div className="flex gap-2">
            <Button type="submit" disabled={create.isPending}>
              {create.isPending ? "Saving…" : "Save search"}
            </Button>
            <Button type="button" variant="outline" onClick={() => setOpen(false)}>
              Cancel
            </Button>
          </div>
        </form>
      )}
    </div>
  );
}
