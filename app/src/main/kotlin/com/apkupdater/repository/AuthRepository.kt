package com.apkupdater.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.tasks.await

class AuthRepository(
    private val auth: FirebaseAuth,
    private val database: FirebaseDatabase
) {

    private var blockListener: ValueEventListener? = null
    private var blockRef: DatabaseReference? = null

    fun currentUser(): FirebaseUser? = auth.currentUser

    suspend fun signInWithGoogle(idToken: String): FirebaseUser {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        return auth.signInWithCredential(credential).await().user
            ?: throw IllegalStateException("Google sign-in succeeded but user is null.")
    }

    suspend fun isEmailBlocked(email: String): Boolean {
        val snapshot = database.getReference(BLOCKED_EMAILS_PATH)
            .child(email.encodeForFirebaseKey())
            .get()
            .await()
        return snapshot.exists()
    }

    fun observeBlocked(email: String, onBlocked: () -> Unit) {
        clearBlockObserver()
        val ref = database.getReference(BLOCKED_EMAILS_PATH)
            .child(email.encodeForFirebaseKey())
        val listener = ValueEventListenerAdapter { snapshot ->
            if (snapshot.exists()) {
                onBlocked()
            }
        }
        ref.addValueEventListener(listener)
        blockListener = listener
        blockRef = ref
    }

    fun clearBlockObserver() {
        val listener = blockListener ?: return
        val ref = blockRef ?: return
        ref.removeEventListener(listener)
        blockListener = null
        blockRef = null
    }

    fun signOut() {
        clearBlockObserver()
        auth.signOut()
    }

    private fun String.encodeForFirebaseKey(): String =
        replace(".", ",").replace("#", "%23").replace("$", "%24").replace("[", "%5B").replace("]", "%5D")

    private companion object {
        private const val BLOCKED_EMAILS_PATH = "blocked_emails"
    }
}

private class ValueEventListenerAdapter(
    private val onDataChange: (com.google.firebase.database.DataSnapshot) -> Unit
) : ValueEventListener {
    override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
        onDataChange.invoke(snapshot)
    }

    override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
        // Ignored - callers request explicit refreshes when needed.
    }
}
