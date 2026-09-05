package com.kin.familyhealth.core

/**
 * Thrown when pairing is refused because the PARTNER's pairing record still points at a
 * previous identity of ours (e.g. we reinstalled and got a new anonymous uid). The
 * security rules deliberately block overwriting an existing pairing from the outside,
 * so only the partner can fix this from THEIR phone by pairing with our new code.
 * The UI must say exactly that instead of "check the code".
 */
class PairingBlockedByStalePartnerException :
    Exception("Partner's pairing record still points at a previous code")

/**
 * Thrown when the backend refuses even our OWN pairing write or the presence lookup.
 * A signed-in user may always write their own docs under the published rules, so
 * PERMISSION_DENIED there can only mean the Firestore security rules were never
 * published for this project (a production-mode database denies everything by default).
 */
class PairingBackendNotReadyException :
    Exception("Firestore security rules are not published; all writes are denied")

/**
 * Thrown when the typed code does not correspond to any Kin phone (no presence record),
 * or is the user's own code. Without this check a mistyped code would "pair" silently
 * with nobody.
 */
class PairingUnknownCodeException :
    Exception("Code does not match any known Kin phone")
