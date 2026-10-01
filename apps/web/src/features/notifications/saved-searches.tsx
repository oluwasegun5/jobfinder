"use client";

import { Trash2 } from "lucide-react";
import Link from "next/link";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import { problemMessage } from "@/features/auth/api-errors";
import { FormError } from "@/features/auth/form-parts";
import { ApiProblem } from "@/features/profile/queries";

import { describeCriteria, FREQUENCIES, FREQUENCY_LABELS, searchHref } from "./criteria";
import {
  completeSearches,
  useDeleteSavedSearch,
  useSavedSearches,
  useUpdateSavedSearch,
  type SavedSearch,
  type SavedSearchFrequency,
} from "./queries";

function errorText(error: unknown, fallback: string) {
  return problemMessage(error instanceof ApiProblem ? error.problem : undefined, fallback);
}

function Row({ search }: { search: SavedSearch }) {
  const update = useUpdateSavedSearch();
  const remove = useDeleteSavedSearch();
  const [confirming, setConfirming] = useState(false);
  const failure = update.error ?? remove.error;

  const change = (frequency: SavedSearchFrequency) =>
    update.mutate({ id: search.id, body: { name: search.name, criteria: search.criteria, frequency } });

  return (
    <li className="flex flex-col gap-3 rounded-xl border p-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <h2 className="truncate text-base font-medium">{search.name}</h2>
          <p className="text-sm text-muted-foreground">{describeCriteria(search.criteria)}</p>
        </div>
        <Link href={searchHref(search.criteria)} className="text-sm underline">
          See results
        </Link>
      </div>
      <div className="flex flex-wrap items-end gap-3">
        <div className="flex flex-col gap-1.5">
          <label htmlFor={`frequency-${search.id}`} className="text-sm font-medium">
            Email me
          </label>
          <select
            id={`frequency-${search.id}`}
            aria-label={`How often to email "${search.name}"`}
            className="h-9 min-w-0 rounded-lg border border-input bg-transparent px-2 text-base outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 md:text-sm dark:bg-input/30"
            value={search.frequency}
            disabled={update.isPending}
            onChange={(e) => change(e.target.value as SavedSearchFrequency)}
          >
            {FREQUENCIES.map((f) => (
              <option key={f} value={f}>
                {FREQUENCY_LABELS[f]}
              </option>
            ))}
          </select>
        </div>
        {confirming ? (
          <div className="flex items-center gap-2">
            <Button variant="destructive" size="sm" disabled={remove.isPending} onClick={() => remove.mutate(search.id)}>
              Delete &quot;{search.name}&quot;
            </Button>
            <Button variant="outline" size="sm" onClick={() => setConfirming(false)}>
              Keep it
            </Button>
          </div>
        ) : (
          <Button variant="outline" size="sm" aria-label={`Delete ${search.name}`} onClick={() => setConfirming(true)}>
            <Trash2 /> Delete
          </Button>
        )}
      </div>
      <FormError>{failure ? errorText(failure, "That didn't work. Please try again.") : undefined}</FormError>
    </li>
  );
}

export function SavedSearches() {
  const searches = useSavedSearches();

  if (searches.isPending) {
    return (
      <p role="status" className="text-sm text-muted-foreground">
        Loading your saved searches…
      </p>
    );
  }
  if (searches.isError) {
    return (
      <div className="flex flex-col gap-3">
        <FormError>We couldn&apos;t load your saved searches.</FormError>
        <Button variant="outline" className="self-start" onClick={() => void searches.refetch()}>
          Try again
        </Button>
      </div>
    );
  }
  const items = completeSearches(searches.data.items);
  if (items.length === 0) {
    return (
      <div className="flex flex-col gap-2 text-sm">
        <p className="font-medium">No saved searches</p>
        <p className="text-muted-foreground">
          Search for jobs, then choose &quot;Save this search&quot; to get an email when new jobs match.
        </p>
        <Link href="/jobs" className="underline">
          Find jobs
        </Link>
      </div>
    );
  }
  return (
    <ul aria-label="Saved searches" className="flex flex-col gap-3">
      {items.map((search) => (
        <Row key={search.id} search={search} />
      ))}
    </ul>
  );
}
