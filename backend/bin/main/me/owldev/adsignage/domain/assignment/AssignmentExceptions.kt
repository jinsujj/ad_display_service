package me.owldev.adsignage.domain.assignment

/**
 * Thrown when a [DeviceAssignmentService] operation references a device_id
 * that is not present in the devices table. Mapped to HTTP 404.
 */
class DeviceNotFoundException(val deviceId: String) :
    RuntimeException("Device not found: $deviceId")

/**
 * Thrown when a [DeviceAssignmentService] operation references a
 * restaurant_id that is not present in the restaurants table. Mapped to HTTP 404.
 */
class RestaurantNotFoundException(val restaurantId: String) :
    RuntimeException("Restaurant not found: $restaurantId")

/**
 * Thrown when a caller asks for the current assignment of a device that
 * does not currently have one. Mapped to HTTP 404.
 */
class AssignmentNotFoundException(val deviceId: String) :
    RuntimeException("No active assignment for device: $deviceId")

/**
 * AC 9, Sub-AC 1 — thrown by [DeviceUpdateService.applyPatch] when the
 * caller targets a device field that is named in the wire contract but is
 * not yet backed by storage in this build (today: `screenName`,
 * `groupName`). Mapped to HTTP 422 Unprocessable Entity so the admin UI can
 * tell the difference between "I sent a typo" (400) and "the server
 * understood the field but cannot persist it yet".
 *
 * The transitional shape stays self-documenting: when the V10 `devices`
 * table grows the corresponding columns in a sibling sub-AC, the service
 * will replace the throw with an UPDATE and this exception simply stops
 * firing.
 */
class DeviceFieldUnsupportedException(val field: String) :
    RuntimeException("Device field not yet supported: $field")
