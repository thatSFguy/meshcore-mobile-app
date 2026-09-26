package io.github.thatsfguy.meshcore.engine

/**
 * When a contact read is the radio's whole list.
 *
 * The radio opens every read with its total contact count
 * (`RESP_CODE_CONTACTS_START`, `getNumContacts()` — "total, NOT filtered
 * count") and then sends only the records newer than the `since` asked
 * for. So a read that delivered exactly that many is complete, and one
 * that delivered fewer — a changed-only read, or one cut short — is not,
 * whatever the app believed it had asked for.
 *
 * Complete is what licenses forgetting: the app drops any contact a
 * complete read did not return. Getting that wrong is how a handful of
 * records was once published as the whole list, and every other contact,
 * favourites included, vanished from the app.
 */
object ContactSweep {
    /** [delivered] records against the radio's [declared] total; null declared is never complete. */
    fun isComplete(delivered: Int, declared: Long?): Boolean =
        declared != null && declared >= 0 && delivered.toLong() == declared
}
