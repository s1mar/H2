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
