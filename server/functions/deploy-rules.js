/**
 * Publish Firestore security rules with the Firebase Admin SDK.
 *
 * The key generated at Firebase console -> Service accounts is the Admin SDK
 * service account. The Firebase CLI's `deploy --only firestore:rules` first
 * calls the Service Usage API to check Firestore is enabled, which that role
 * cannot do (403). The Admin SDK's Security Rules API needs no such check.
 *
 * Usage: node deploy-rules.js <path-to-firestore.rules>
 * Auth:  GOOGLE_APPLICATION_CREDENTIALS must point at the service-account JSON.
 */
const fs = require("fs");
const path = require("path");
const { initializeApp, applicationDefault } = require("firebase-admin/app");
const { getSecurityRules } = require("firebase-admin/security-rules");

const rulesPath = path.resolve(process.argv[2] || "../../firestore.rules");
const source = fs.readFileSync(rulesPath, "utf8");

initializeApp({ credential: applicationDefault() });

getSecurityRules()
  .releaseFirestoreRulesetFromSource(source)
  .then((ruleset) => {
    console.log(`Published Firestore rules: ${ruleset.name} (${source.length} bytes)`);
  })
  .catch((err) => {
    console.error("::error::Failed to publish Firestore rules:", err.message || err);
    process.exit(1);
  });
