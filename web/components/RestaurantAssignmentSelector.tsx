"use client";

/**
 * Restaurant assignment UI component (AC 40202, Sub-AC 2).
 *
 * Goal:
 *   "Build restaurant assignment UI component with dropdown/selector to choose
 *    target restaurant for a device."
 *
 * What this component does:
 *   1. Fetches the full restaurant list on mount via [listRestaurants].
 *   2. Renders a native <select> dropdown so the operator can pick a target
 *      restaurant for the given [deviceId]. We use a native select (not a
 *      custom widget) because (a) it works without any extra deps in the
 *      hackathon stack and (b) it is fully keyboard accessible, mobile
 *      friendly, and gives us long-list virtualisation for free on touch
 *      devices the operator might use behind a bar.
 *   3. Shows the current assignment (if known) so the operator can see what
 *      they're about to change before they change it. The dropdown is
 *      pre-selected to that restaurant.
 *   4. Submits the change via [assignDeviceToRestaurant] (PUT
 *      /api/devices/{id}/assignment), which is the entry point that triggers
 *      the SSE remap broadcast on the backend (demo scenario #3).
 *   5. Surfaces inline loading / error / success feedback so the operator
 *      knows the SSE remap actually happened — without forcing a page reload.
 *
 * Composition:
 *   This component is intentionally self-contained and prop-driven so it can
 *   be embedded into the device detail page (Sub-AC 3, owned in a sibling AC)
 *   without further refactoring. Server-side data (the device row + its
 *   current restaurant) is passed down as props; the restaurant list is
 *   fetched client-side because it changes independently of the device row.
 */

import { useEffect, useMemo, useState } from "react";
import { ApiError } from "@/lib/api";
import {
  listRestaurants,
  type RestaurantListItem,
} from "@/lib/restaurants";
import {
  assignDeviceToRestaurant,
  type DeviceAssignmentResult,
} from "@/lib/assignments";

/** Props for [RestaurantAssignmentSelector]. */
export interface RestaurantAssignmentSelectorProps {
  /** UUID of the device whose assignment is being edited. Required. */
  deviceId: string;
  /** Optional human-readable label for display ("Fridge #2"). */
  deviceName?: string;
  /** Current restaurant id, if any — used to preselect the dropdown. */
  currentRestaurantId?: string | null;
  /** Current restaurant name, if any — shown in the "Currently assigned" line. */
  currentRestaurantName?: string | null;
  /**
   * Optional pre-fetched restaurant list. If supplied, the component skips the
   * client-side fetch — useful when the parent page already has the data
   * (e.g. a Server Component rendering both the device detail and this UI).
   */
  initialRestaurants?: RestaurantListItem[];
  /**
   * Called after a successful assignment. The parent can use this to refresh
   * its own state (e.g. re-fetch the device row, update a status pill) without
   * a full page reload.
   */
  onAssigned?: (result: DeviceAssignmentResult) => void;
}

type LoadState =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "ready"; restaurants: RestaurantListItem[] }
  | { kind: "error"; message: string };

type SubmitState =
  | { kind: "idle" }
  | { kind: "submitting" }
  | { kind: "success"; result: DeviceAssignmentResult }
  | { kind: "error"; message: string };

export function RestaurantAssignmentSelector(
  props: RestaurantAssignmentSelectorProps,
) {
  const {
    deviceId,
    deviceName,
    currentRestaurantId,
    currentRestaurantName,
    initialRestaurants,
    onAssigned,
  } = props;

  // Restaurant list lifecycle. If the parent passed an initial list we skip
  // the network call entirely and start in `ready`.
  const [loadState, setLoadState] = useState<LoadState>(() =>
    initialRestaurants && initialRestaurants.length > 0
      ? { kind: "ready", restaurants: initialRestaurants }
      : { kind: "idle" },
  );

  // The currently selected option in the dropdown. Empty string = the
  // "Choose a restaurant…" placeholder is selected.
  const [selectedId, setSelectedId] = useState<string>(
    currentRestaurantId ?? "",
  );

  // Submit lifecycle (POST/PUT to /api/devices/{id}/assignment).
  const [submitState, setSubmitState] = useState<SubmitState>({ kind: "idle" });

  // Fetch restaurants on mount (unless pre-supplied).
  useEffect(() => {
    if (loadState.kind !== "idle") return;
    let cancelled = false;
    setLoadState({ kind: "loading" });
    listRestaurants()
      .then((restaurants) => {
        if (cancelled) return;
        setLoadState({ kind: "ready", restaurants });
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setLoadState({ kind: "error", message: describeError(err) });
      });
    return () => {
      cancelled = true;
    };
  }, [loadState.kind]);

  // Keep the dropdown in sync if the parent passes a fresh currentRestaurantId
  // after a successful remap (e.g. via revalidation).
  useEffect(() => {
    if (currentRestaurantId !== undefined && currentRestaurantId !== null) {
      setSelectedId(currentRestaurantId);
    }
  }, [currentRestaurantId]);

  // Allowed dropdown options + a derived "is the selection actually a change?"
  // flag for the submit button.
  const restaurants = loadState.kind === "ready" ? loadState.restaurants : [];
  const hasNoOptions = loadState.kind === "ready" && restaurants.length === 0;

  const isDirty = useMemo(() => {
    if (!selectedId) return false;
    return selectedId !== (currentRestaurantId ?? "");
  }, [selectedId, currentRestaurantId]);

  const submitDisabled =
    !deviceId ||
    !selectedId ||
    !isDirty ||
    submitState.kind === "submitting" ||
    loadState.kind !== "ready";

  async function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (submitDisabled) return;
    setSubmitState({ kind: "submitting" });
    try {
      const result = await assignDeviceToRestaurant(deviceId, selectedId);
      setSubmitState({ kind: "success", result });
      onAssigned?.(result);
    } catch (err) {
      setSubmitState({ kind: "error", message: describeError(err) });
    }
  }

  return (
    <form
      className="assignment-selector"
      aria-label="Assign device to restaurant"
      onSubmit={handleSubmit}
    >
      <header className="assignment-selector__header">
        <strong>Restaurant assignment</strong>
        <span className="muted" style={{ fontSize: 12 }}>
          {deviceName ? `Device: ${deviceName}` : `Device: ${deviceId}`}
        </span>
      </header>

      <div className="assignment-selector__current">
        <span className="muted">Currently assigned:</span>{" "}
        {currentRestaurantId ? (
          <strong>
            {currentRestaurantName || currentRestaurantId}
          </strong>
        ) : (
          <span className="pill pill-warn">Unassigned</span>
        )}
      </div>

      <label
        className="assignment-selector__label"
        htmlFor={`restaurant-select-${deviceId}`}
      >
        Target restaurant
      </label>
      <select
        id={`restaurant-select-${deviceId}`}
        className="assignment-selector__select"
        value={selectedId}
        disabled={loadState.kind !== "ready" || submitState.kind === "submitting"}
        onChange={(e) => setSelectedId(e.target.value)}
        aria-describedby={`restaurant-select-help-${deviceId}`}
      >
        <option value="">
          {loadState.kind === "loading"
            ? "Loading restaurants…"
            : hasNoOptions
              ? "No restaurants available"
              : "Choose a restaurant…"}
        </option>
        {restaurants.map((r) => (
          <option key={r.restaurantId} value={r.restaurantId}>
            {labelFor(r)}
          </option>
        ))}
      </select>
      <div
        id={`restaurant-select-help-${deviceId}`}
        className="muted"
        style={{ fontSize: 12, marginTop: 4 }}
      >
        Picking a different restaurant and clicking Assign immediately remaps
        the device. The device&apos;s player updates within seconds via SSE.
      </div>

      {loadState.kind === "error" && (
        <div className="notice notice-error" role="alert">
          Failed to load restaurants: {loadState.message}
        </div>
      )}

      {submitState.kind === "error" && (
        <div className="notice notice-error" role="alert">
          Assignment failed: {submitState.message}
        </div>
      )}

      {submitState.kind === "success" && (
        <div
          className="notice"
          role="status"
          style={{
            borderColor: "rgba(74, 222, 128, 0.5)",
            background: "rgba(74, 222, 128, 0.08)",
            color: "var(--ok)",
          }}
        >
          Assigned. New active assignment{" "}
          <code>{shortId(submitState.result.assignmentId)}</code> at{" "}
          {submitState.result.assignedAt}.
        </div>
      )}

      <div className="toolbar" style={{ marginTop: 12 }}>
        <button type="submit" className="btn" disabled={submitDisabled}>
          {submitState.kind === "submitting" ? "Assigning…" : "Assign"}
        </button>
        {isDirty && submitState.kind !== "submitting" && (
          <button
            type="button"
            className="btn"
            onClick={() => {
              setSelectedId(currentRestaurantId ?? "");
              setSubmitState({ kind: "idle" });
            }}
          >
            Reset
          </button>
        )}
      </div>
    </form>
  );
}

/* ------------------------------------------------------------------ helpers */

function labelFor(r: RestaurantListItem): string {
  if (r.address && r.restaurantName) {
    return `${r.restaurantName} — ${r.address}`;
  }
  return r.restaurantName || r.restaurantId;
}

function shortId(id: string | undefined | null): string {
  if (!id) return "—";
  if (id.length <= 12) return id;
  return `${id.slice(0, 8)}…${id.slice(-4)}`;
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) {
    return `HTTP ${err.status} (${err.url})`;
  }
  if (err instanceof Error) return err.message;
  return "unknown error";
}

export default RestaurantAssignmentSelector;
