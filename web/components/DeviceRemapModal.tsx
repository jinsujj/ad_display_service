"use client";

/**
 * Device remap modal (AC 9, Sub-AC 2).
 *
 * Goal:
 *   "Add/extend admin device list page in Next.js (web/) with remap UI controls
 *    (edit action, form/modal) on the device list view."
 *
 * What this component does:
 *   - Renders a centered modal dialog over the device list page when an
 *     operator clicks the per-row "Edit" / "Reassign" affordance.
 *   - Hosts the remap form (target-restaurant <select> + Save / Cancel) so the
 *     edit action lives in a focused, dismissible surface instead of an
 *     in-table inline editor — much friendlier when an operator is remapping
 *     multiple devices in a row from a touch device behind a bar.
 *   - Is fully prop-driven: the parent (the device list page's table client)
 *     owns the device + restaurant data and the actual mutation handler. This
 *     modal is a pure presentational shell over the same form fields that the
 *     existing inline editor uses, so the demo flow (operator picks a
 *     restaurant → backend PUT → SSE MAPPING_CHANGED → player swaps playlist)
 *     stays identical.
 *
 * Accessibility:
 *   - role="dialog" + aria-modal="true" + aria-labelledby tying the heading.
 *   - Closes on backdrop click, Escape, or Cancel; Save is disabled when the
 *     selected restaurant is the one currently mapped (no-op guard).
 *   - First focusable element (the <select>) is focused on open so keyboard
 *     users can immediately pick a target without a tab dance.
 *
 * Why a modal (vs. the existing inline row editor):
 *   The inline editor remains in [DevicesTableClient] for power users and
 *   accessibility fallback, but the modal gives a clearer "this is the edit
 *   action" affordance — clicking Edit lifts the form out of the table and
 *   centers it, matching the explicit "edit action, form/modal" requirement
 *   in the AC. Both paths converge on the same `onSave(restaurantId)` handler
 *   so the underlying remap flow is unchanged.
 */

import { useEffect, useId, useMemo, useRef, useState } from "react";

import type { DeviceListItem } from "@/lib/devices";
import type { RestaurantListItem } from "@/lib/restaurants";

export interface DeviceRemapModalProps {
  /** Device being remapped. The modal renders nothing if this is null. */
  device: DeviceListItem | null;
  /** Restaurant options for the target dropdown. */
  restaurants: RestaurantListItem[];
  /** True while the parent is awaiting the backend PUT — disables the form. */
  submitting?: boolean;
  /** Inline error from the parent's last submit attempt, if any. */
  errorMessage?: string | null;
  /**
   * Called when the operator confirms a target restaurant. The parent owns
   * the actual mutation (PATCH /api/devices/{deviceId} with `{ restaurantId }`,
   * AC 9 Sub-AC 3) + list refresh on success.
   */
  onSave: (deviceId: string, restaurantId: string) => void;
  /** Called when the modal should close (Cancel, backdrop, Escape, X). */
  onClose: () => void;
}

/**
 * Modal dialog for remapping a device to a restaurant.
 *
 * The component is rendered conditionally on `device != null`; when null it
 * returns null so callers can keep a single piece of state (`editingDevice`)
 * to drive open/close.
 */
export function DeviceRemapModal(props: DeviceRemapModalProps) {
  const {
    device,
    restaurants,
    submitting = false,
    errorMessage = null,
    onSave,
    onClose,
  } = props;

  const titleId = useId();
  const selectId = useId();
  const selectRef = useRef<HTMLSelectElement | null>(null);

  // Local selection state — initialised to the device's current restaurant so
  // the dropdown opens on a sensible default. Reset every time a different
  // device is opened.
  const currentId = device?.currentRestaurant?.restaurantId ?? "";
  const [selectedId, setSelectedId] = useState<string>(currentId);

  useEffect(() => {
    if (!device) return;
    setSelectedId(device.currentRestaurant?.restaurantId ?? "");
  }, [device]);

  // Focus the select when the modal opens so keyboard users can immediately
  // pick a restaurant without an extra Tab.
  useEffect(() => {
    if (!device) return;
    // Defer to the next frame so the element is mounted + visible.
    const id = window.requestAnimationFrame(() => {
      selectRef.current?.focus();
    });
    return () => window.cancelAnimationFrame(id);
  }, [device]);

  // Close on Escape — only attach the listener while the modal is open so we
  // don't leak handlers for every page visit.
  useEffect(() => {
    if (!device) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !submitting) {
        onClose();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [device, onClose, submitting]);

  const isDirty = useMemo(() => {
    if (!selectedId) return false;
    return selectedId !== currentId;
  }, [selectedId, currentId]);

  const saveDisabled =
    !selectedId || !isDirty || submitting || restaurants.length === 0;

  if (!device) return null;

  const current = device.currentRestaurant;

  return (
    <div
      className="modal-backdrop"
      role="presentation"
      onMouseDown={(e) => {
        // Close only when the click started on the backdrop itself, not when
        // the operator drags out of the dialog content.
        if (e.target === e.currentTarget && !submitting) {
          onClose();
        }
      }}
    >
      <div
        className="modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
      >
        <header className="modal__header">
          <h2 id={titleId} className="modal__title">
            Remap device
          </h2>
          <button
            type="button"
            className="modal__close"
            onClick={onClose}
            disabled={submitting}
            aria-label="Close remap dialog"
          >
            ×
          </button>
        </header>

        <form
          className="modal__body"
          onSubmit={(e) => {
            e.preventDefault();
            if (!saveDisabled) onSave(device.deviceId, selectedId);
          }}
        >
          <div className="modal__row">
            <span className="muted">Device:</span>{" "}
            <strong>{device.deviceName || "(unnamed)"}</strong>
            <div className="muted" style={{ fontSize: 12 }}>
              {device.deviceId}
            </div>
          </div>

          <div className="modal__row">
            <span className="muted">Currently assigned:</span>{" "}
            {current ? (
              <strong>
                {current.restaurantName || current.restaurantId}
              </strong>
            ) : (
              <span className="pill pill-warn">Unassigned</span>
            )}
          </div>

          <label className="assignment-selector__label" htmlFor={selectId}>
            Target restaurant
          </label>
          <select
            id={selectId}
            ref={selectRef}
            className="assignment-selector__select"
            value={selectedId}
            disabled={submitting || restaurants.length === 0}
            onChange={(e) => setSelectedId(e.target.value)}
          >
            <option value="">
              {restaurants.length === 0
                ? "No restaurants available"
                : "Choose a restaurant…"}
            </option>
            {restaurants.map((r) => (
              <option key={r.restaurantId} value={r.restaurantId}>
                {labelFor(r)}
              </option>
            ))}
          </select>

          <p className="muted" style={{ fontSize: 12, margin: "4px 0 0" }}>
            Saving immediately remaps the device. The player updates within
            seconds via SSE.
          </p>

          {errorMessage && (
            <div className="notice notice-error" role="alert">
              Remap failed: {errorMessage}
            </div>
          )}

          <div className="modal__footer">
            <button
              type="button"
              className="btn"
              onClick={onClose}
              disabled={submitting}
            >
              Cancel
            </button>
            <button type="submit" className="btn" disabled={saveDisabled}>
              {submitting ? "Saving…" : "Save"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ helpers */

function labelFor(r: RestaurantListItem): string {
  if (r.address && r.restaurantName) {
    return `${r.restaurantName} — ${r.address}`;
  }
  return r.restaurantName || r.restaurantId;
}

export default DeviceRemapModal;
