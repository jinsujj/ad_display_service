"use client";

/**
 * Interactive devices table (AC 40203, Sub-AC 3 + AC 9, Sub-AC 3).
 *
 * Goal:
 *   "Implement mapping change handler that calls backend API to update
 *    device-to-restaurant mapping and refreshes the list."
 *
 *   AC 9, Sub-AC 3 update: "Wire the admin device list remap UI to call
 *   PATCH /api/devices/{deviceId} via the API client and handle success /
 *   error states with list refresh." This sub-AC swaps the underlying call
 *   from the legacy `PUT /api/devices/{id}/assignment` route to the generic
 *   `PATCH /api/devices/{deviceId}` umbrella route owned by AC 9, Sub-AC 1.
 *   The user-visible flow (operator clicks Edit → picks restaurant → Save →
 *   list refreshes) is unchanged; only the wire call moves.
 *
 * What this component does:
 *   1. Receives an initial server-fetched [DeviceListItem] list and a
 *      [RestaurantListItem] list as props (the Devices page Server Component
 *      fetches both before render). This lets us paint the table immediately
 *      with no client spinner.
 *   2. Renders one row per device with an inline "Reassign" affordance —
 *      clicking it expands a per-row editor (a <select> dropdown of
 *      restaurants + Save / Cancel) without leaving the page.
 *   3. Owns the **mapping change handler** ([handleMappingChange] below) which
 *      is the centerpiece of this Sub-AC:
 *        a) calls the backend `PATCH /api/devices/{deviceId}` via
 *           [patchDevice] (lib/devices.ts) with `{ restaurantId }`;
 *        b) optimistically merges the new restaurant into the row so the
 *           operator sees the change instantly even before the next refresh;
 *        c) calls `router.refresh()` so the Server Component re-fetches
 *           `GET /api/devices` and the list reflects the authoritative
 *           backend state — i.e. "refreshes the list" per the AC verbatim.
 *      On error, the row falls back to its previous state and the operator
 *      sees an inline error notice; nothing is silently swallowed.
 *
 * Why a client component table (vs. a per-row link to a detail page):
 *   The acceptance criterion explicitly says "refreshes the list" — i.e. the
 *   change must be reflected on the list view itself, not on a separate
 *   detail page. Operators behind a bar will be remapping multiple devices in
 *   sequence; round-tripping through a detail page per device would be
 *   painful. An inline editor keeps them on the list and combined with
 *   `router.refresh()` gives us authoritative server data after each change.
 */

import { useCallback, useState, useTransition } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

import { ApiError } from "@/lib/api";
import {
  patchDevice,
  type DevicePatchResponse,
  type DeviceListItem,
  type CurrentRestaurant,
} from "@/lib/devices";
import type { RestaurantListItem } from "@/lib/restaurants";
import { DeviceRemapModal } from "./DeviceRemapModal";

export interface DevicesTableClientProps {
  /** Server-fetched device rows. Used as the initial table state. */
  initialDevices: DeviceListItem[];
  /**
   * Restaurant list for the inline reassign dropdowns. If empty, rows render
   * without an editor and surface a hint that no restaurants are available.
   */
  restaurants: RestaurantListItem[];
}

/** Per-row UI state for the inline editor / async submission. */
type RowSubmitState =
  | { kind: "idle" }
  | { kind: "submitting" }
  | { kind: "success"; result: DevicePatchResponse; at: number }
  | { kind: "error"; message: string };

/** Per-row editor state (which row is currently "open" for editing, etc). */
interface RowEditState {
  /** Currently selected restaurant id in the dropdown for this row. */
  selectedId: string;
  /** Whether the inline editor is expanded. */
  open: boolean;
}

const DEFAULT_EDIT: RowEditState = { selectedId: "", open: false };

export function DevicesTableClient(props: DevicesTableClientProps) {
  const { initialDevices, restaurants } = props;
  const router = useRouter();

  // Local mirror of the server-rendered list. We optimistically update this
  // immediately on a successful PUT so the operator sees the change without
  // waiting for the round-trip back to `GET /api/devices`. The mirror is then
  // overwritten on the next render after `router.refresh()` brings in fresh
  // server data — which is the authoritative source.
  const [devices, setDevices] = useState<DeviceListItem[]>(initialDevices);

  // Per-row editor + submission state, keyed by deviceId. We store these
  // outside the device list so that re-rendering after a server refresh does
  // not wipe out a transient "Saved" notice the operator is still reading.
  const [editStates, setEditStates] = useState<Record<string, RowEditState>>({});
  const [submitStates, setSubmitStates] = useState<Record<string, RowSubmitState>>({});

  // Modal-based remap (AC 9, Sub-AC 2). The list page now exposes an explicit
  // "Edit" action per row that opens a focused modal dialog instead of (or in
  // addition to) the existing inline editor. We track which device the modal
  // is currently editing — null means closed.
  const [editingDeviceId, setEditingDeviceId] = useState<string | null>(null);
  const editingDevice = editingDeviceId
    ? devices.find((d) => d.deviceId === editingDeviceId) ?? null
    : null;

  // `useTransition` lets us call `router.refresh()` without blocking the UI;
  // we surface `isRefreshing` next to the table header so the operator knows
  // the list is being re-fetched after a successful change.
  const [isRefreshing, startRefresh] = useTransition();

  const setEdit = useCallback(
    (deviceId: string, patch: Partial<RowEditState>) => {
      setEditStates((prev) => {
        const cur = prev[deviceId] ?? DEFAULT_EDIT;
        return { ...prev, [deviceId]: { ...cur, ...patch } };
      });
    },
    [],
  );

  const setSubmit = useCallback(
    (deviceId: string, state: RowSubmitState) => {
      setSubmitStates((prev) => ({ ...prev, [deviceId]: state }));
    },
    [],
  );

  /**
   * The mapping change handler — this is the function the AC is asking for.
   * Called when the operator confirms a restaurant pick for a device.
   *
   * Flow:
   *   1. Mark the row as submitting (disables the form).
   *   2. PATCH /api/devices/{deviceId} with `{ restaurantId }` via
   *      [patchDevice]. Using the umbrella PATCH route (AC 9, Sub-AC 1)
   *      means the same wire call will absorb future device-level fields
   *      (screenName, groupName, …) without an extra controller hop.
   *   3. On success:
   *      - optimistically patch the row's `currentRestaurant` so the table
   *        reflects the change before the next server fetch lands;
   *      - close the inline editor and the modal (if open) so the operator
   *        falls back to the list with the new mapping visible;
   *      - kick off `router.refresh()` so the Server Component re-runs
   *        `listDevices()` and the list is replaced with authoritative
   *        backend data ("refreshes the list").
   *   4. On error: keep the editor / modal open, show an inline error so the
   *      operator can correct + retry without losing context.
   */
  const handleMappingChange = useCallback(
    async (deviceId: string, restaurantId: string) => {
      if (!deviceId || !restaurantId) return;

      const targetRestaurant = restaurants.find(
        (r) => r.restaurantId === restaurantId,
      );

      setSubmit(deviceId, { kind: "submitting" });

      try {
        const result = await patchDevice(deviceId, { restaurantId });

        // Optimistic local update: replace this device's currentRestaurant
        // with the just-assigned one so the row reflects the new mapping
        // before `router.refresh()` round-trips. We use the dropdown's
        // restaurant metadata (name/address) for the labels — the backend
        // result only carries ids/timestamps. If the PATCH response unexpectedly
        // omits the resolved restaurantId (e.g. an unassign-style response in
        // a future sub-AC), we fall back to the operator-selected id so the
        // row still reflects what the operator just clicked.
        setDevices((prev) =>
          prev.map((d) =>
            d.deviceId === deviceId
              ? {
                  ...d,
                  currentRestaurant: buildOptimisticAssignment(
                    { ...result, restaurantId: result.restaurantId ?? restaurantId },
                    targetRestaurant,
                  ),
                }
              : d,
          ),
        );

        setSubmit(deviceId, { kind: "success", result, at: Date.now() });
        setEdit(deviceId, { open: false });

        // If the change came from the modal path, close the modal too so the
        // operator falls back to the list with the new mapping visible.
        setEditingDeviceId((cur) => (cur === deviceId ? null : cur));

        // Refresh the Server Component so the list shows authoritative data.
        startRefresh(() => {
          router.refresh();
        });
      } catch (err) {
        setSubmit(deviceId, {
          kind: "error",
          message: describeError(err),
        });
      }
    },
    [restaurants, router, setEdit, setSubmit, startRefresh],
  );

  /**
   * Modal entry point — opens the dedicated remap dialog for `deviceId`.
   * Clears any stale per-row error so the modal opens in a clean state.
   */
  const openRemapModal = useCallback(
    (deviceId: string) => {
      setSubmit(deviceId, { kind: "idle" });
      setEditingDeviceId(deviceId);
    },
    [setSubmit],
  );

  const closeRemapModal = useCallback(() => {
    setEditingDeviceId(null);
  }, []);

  return (
    <div>
      <div className="toolbar" style={{ marginBottom: 8 }}>
        {isRefreshing ? (
          <span className="muted" role="status">
            Refreshing list…
          </span>
        ) : (
          <span className="muted">
            {devices.length} device{devices.length === 1 ? "" : "s"}
          </span>
        )}
      </div>

      <table className="data-table" aria-label="Devices">
        <thead>
          <tr>
            <th scope="col">Device</th>
            <th scope="col">Device ID</th>
            <th scope="col">Registered</th>
            <th scope="col">Current restaurant</th>
            <th scope="col">Mapped at</th>
            <th scope="col" style={{ width: 260 }}>
              Remap
            </th>
          </tr>
        </thead>
        <tbody>
          {devices.map((device) => {
            const edit = editStates[device.deviceId] ?? DEFAULT_EDIT;
            const submit = submitStates[device.deviceId] ?? { kind: "idle" };
            return (
              <DeviceRow
                key={device.deviceId || device.deviceName}
                device={device}
                restaurants={restaurants}
                edit={edit}
                submit={submit}
                onToggleEdit={(open) =>
                  setEdit(device.deviceId, {
                    open,
                    // when opening, preselect the current restaurant so the
                    // dropdown is in a sensible default state.
                    selectedId: open
                      ? device.currentRestaurant?.restaurantId ?? ""
                      : "",
                  })
                }
                onSelectRestaurant={(id) =>
                  setEdit(device.deviceId, { selectedId: id })
                }
                onSave={() =>
                  handleMappingChange(device.deviceId, edit.selectedId)
                }
                onClearStatus={() => setSubmit(device.deviceId, { kind: "idle" })}
                onOpenModal={() => openRemapModal(device.deviceId)}
              />
            );
          })}
        </tbody>
      </table>

      {/* Modal-based remap surface (AC 9, Sub-AC 2). Renders only while
          `editingDevice` is non-null. The modal calls `handleMappingChange`
          via its onSave so it shares the same PUT + refresh path as the
          inline editor. */}
      <DeviceRemapModal
        device={editingDevice}
        restaurants={restaurants}
        submitting={
          editingDeviceId
            ? (submitStates[editingDeviceId]?.kind ?? "idle") === "submitting"
            : false
        }
        errorMessage={
          editingDeviceId &&
          submitStates[editingDeviceId]?.kind === "error"
            ? (submitStates[editingDeviceId] as { kind: "error"; message: string })
                .message
            : null
        }
        onSave={(deviceId, restaurantId) =>
          handleMappingChange(deviceId, restaurantId)
        }
        onClose={closeRemapModal}
      />
    </div>
  );
}

/* --------------------------------------------------------------- row */

interface DeviceRowProps {
  device: DeviceListItem;
  restaurants: RestaurantListItem[];
  edit: RowEditState;
  submit: RowSubmitState;
  onToggleEdit: (open: boolean) => void;
  onSelectRestaurant: (id: string) => void;
  onSave: () => void;
  onClearStatus: () => void;
  /** Open the modal-based remap dialog for this row (AC 9, Sub-AC 2). */
  onOpenModal: () => void;
}

function DeviceRow(props: DeviceRowProps) {
  const {
    device,
    restaurants,
    edit,
    submit,
    onToggleEdit,
    onSelectRestaurant,
    onSave,
    onClearStatus,
    onOpenModal,
  } = props;

  const current = device.currentRestaurant;
  const submitting = submit.kind === "submitting";

  // The operator can only save if they picked a *different* restaurant than
  // the one currently assigned. Saving the same id would just be a no-op
  // round-trip to the backend.
  const isDirty = !!edit.selectedId && edit.selectedId !== (current?.restaurantId ?? "");
  const saveDisabled =
    !edit.selectedId || !isDirty || submitting || restaurants.length === 0;

  return (
    <>
      <tr>
        <td>
          <strong>{device.deviceName || "(unnamed)"}</strong>
        </td>
        <td className="id" title={device.deviceId}>
          <Link href={`/devices/${encodeURIComponent(device.deviceId)}`}>
            {shortId(device.deviceId)}
          </Link>
        </td>
        <td>
          <span className="muted">{formatDate(device.registeredAt)}</span>
        </td>
        <td>
          {current ? (
            <div>
              <div>
                <strong>{current.restaurantName || "(unnamed restaurant)"}</strong>
              </div>
              <div className="muted" style={{ fontSize: 12 }}>
                {shortId(current.restaurantId)}
                {current.address ? ` · ${current.address}` : ""}
              </div>
            </div>
          ) : (
            <span className="pill pill-warn">Unassigned</span>
          )}
        </td>
        <td>
          {current ? (
            <span className="muted">{formatDate(current.assignedAt)}</span>
          ) : (
            <span className="muted">—</span>
          )}
        </td>
        <td>
          {!edit.open && (
            <div className="toolbar" style={{ flexWrap: "wrap" }}>
              {/* Primary edit action — opens the dedicated remap modal
                  (AC 9, Sub-AC 2). Uses the pencil glyph to read as an
                  "edit" affordance at a glance. */}
              <button
                type="button"
                className="btn"
                onClick={onOpenModal}
                disabled={submitting || restaurants.length === 0}
                title={
                  restaurants.length === 0
                    ? "No restaurants available to remap"
                    : "Open the remap dialog for this device"
                }
                aria-label={`Edit mapping for ${device.deviceName || device.deviceId}`}
              >
                <span aria-hidden="true">✎</span> Edit
              </button>
              {/* Secondary inline editor — kept for power users who prefer
                  to stay in the table without opening a dialog. */}
              <button
                type="button"
                className="btn"
                onClick={() => onToggleEdit(true)}
                disabled={submitting || restaurants.length === 0}
                title="Reassign inline without opening the modal"
              >
                {current ? "Reassign" : "Assign"}
              </button>
            </div>
          )}
          {edit.open && (
            <span className="muted" style={{ fontSize: 12 }}>
              Editing…
            </span>
          )}
        </td>
      </tr>

      {edit.open && (
        <tr className="device-row-editor">
          <td colSpan={6}>
            <form
              className="assignment-selector"
              style={{ maxWidth: "100%" }}
              onSubmit={(e) => {
                e.preventDefault();
                if (!saveDisabled) onSave();
              }}
              aria-label={`Reassign ${device.deviceName || device.deviceId}`}
            >
              <div className="assignment-selector__current">
                <span className="muted">Currently assigned:</span>{" "}
                {current ? (
                  <strong>
                    {current.restaurantName || current.restaurantId}
                  </strong>
                ) : (
                  <span className="pill pill-warn">Unassigned</span>
                )}
              </div>

              <label
                className="assignment-selector__label"
                htmlFor={`reassign-select-${device.deviceId}`}
              >
                Target restaurant
              </label>
              <select
                id={`reassign-select-${device.deviceId}`}
                className="assignment-selector__select"
                value={edit.selectedId}
                disabled={submitting || restaurants.length === 0}
                onChange={(e) => onSelectRestaurant(e.target.value)}
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

              {submit.kind === "error" && (
                <div className="notice notice-error" role="alert">
                  Assignment failed: {submit.message}
                </div>
              )}

              <div className="toolbar" style={{ marginTop: 8 }}>
                <button type="submit" className="btn" disabled={saveDisabled}>
                  {submitting ? "Saving…" : "Save"}
                </button>
                <button
                  type="button"
                  className="btn"
                  onClick={() => {
                    onClearStatus();
                    onToggleEdit(false);
                  }}
                  disabled={submitting}
                >
                  Cancel
                </button>
              </div>
            </form>
          </td>
        </tr>
      )}

      {!edit.open && submit.kind === "success" && (
        <tr className="device-row-status">
          <td colSpan={6}>
            <div
              className="notice"
              role="status"
              style={{
                borderColor: "rgba(74, 222, 128, 0.5)",
                background: "rgba(74, 222, 128, 0.08)",
                color: "var(--ok)",
                marginBottom: 0,
              }}
            >
              Mapping updated.
              {submit.result.assignmentId ? (
                <>
                  {" "}Active assignment{" "}
                  <code>{shortId(submit.result.assignmentId)}</code>
                  {submit.result.assignedAt
                    ? ` at ${submit.result.assignedAt}`
                    : ""}
                  .
                </>
              ) : null}{" "}
              <button
                type="button"
                className="btn"
                style={{ marginLeft: 8 }}
                onClick={onClearStatus}
              >
                Dismiss
              </button>
            </div>
          </td>
        </tr>
      )}
    </>
  );
}

/* ------------------------------------------------------------ helpers */

/**
 * Builds the optimistic [CurrentRestaurant] entry to splice into a row after a
 * successful PATCH, before the server-side refresh round-trip lands. Falls
 * back gracefully if the dropdown row metadata is missing or the backend
 * omitted `assignedAt` (older response shapes).
 */
function buildOptimisticAssignment(
  result: { restaurantId: string | null; assignedAt: string | null },
  meta: RestaurantListItem | undefined,
): CurrentRestaurant {
  return {
    restaurantId: result.restaurantId ?? meta?.restaurantId ?? "",
    restaurantName: meta?.restaurantName ?? "",
    address: meta?.address ?? null,
    assignedAt: result.assignedAt ?? new Date().toISOString(),
  };
}

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

function formatDate(iso: string | undefined | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toISOString().replace("T", " ").slice(0, 16) + " UTC";
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return `HTTP ${err.status} (${err.url})`;
  if (err instanceof Error) return err.message;
  return "unknown error";
}

export default DevicesTableClient;
