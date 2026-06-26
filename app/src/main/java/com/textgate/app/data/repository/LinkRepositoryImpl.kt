package com.textgate.app.data.repository

import com.textgate.app.data.firebase.FirestoreDataSource
import com.textgate.app.domain.model.AccountLink
import com.textgate.app.domain.model.LinkPermissions
import com.textgate.app.domain.model.LinkState
import com.textgate.app.domain.model.LocationRequest
import com.textgate.app.domain.repository.LinkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LinkRepositoryImpl(private val firestore: FirestoreDataSource) : LinkRepository {

    override suspend fun publishDirectoryEntry(phoneNumber: String, uid: String, name: String) =
        firestore.publishPhoneDirectoryEntry(phoneNumber, uid, name)

    override suspend fun lookupByPhone(phoneNumber: String) =
        firestore.lookupPhoneDirectory(phoneNumber)

    override suspend fun invite(
        myUid: String,
        myName: String,
        myPhone: String,
        otherUid: String,
        otherName: String,
        otherPhone: String,
        permissions: LinkPermissions,
    ) = firestore.createLinkInvite(
        myUid, myName, myPhone, otherUid, otherName, otherPhone, permissions,
    )

    override suspend fun setState(
        myUid: String,
        otherUid: String,
        state: LinkState,
        alsoUpdateOtherSide: Boolean,
    ) = firestore.setLinkState(myUid, otherUid, state, alsoUpdateOtherSide)

    override suspend fun setPermissions(myUid: String, otherUid: String, permissions: LinkPermissions) =
        firestore.setLinkPermissions(myUid, otherUid, permissions)

    override suspend fun remove(myUid: String, otherUid: String) =
        firestore.deleteLink(myUid, otherUid)

    override fun getLinks(uid: String): Flow<List<AccountLink>> =
        firestore.getLinks(uid).map { list -> list.map { it.toDomain() } }

    override suspend fun activeLinks(uid: String): List<AccountLink> =
        firestore.getLinksOnce(uid).getOrDefault(emptyList())
            .map { it.toDomain() }
            .filter { it.state == LinkState.ACTIVE }

    override suspend fun requestLocation(targetUid: String, requesterUid: String, requesterName: String) =
        firestore.createLocationRequest(targetUid, requesterUid, requesterName)

    override fun watchLocationRequest(targetUid: String, requestId: String): Flow<LocationRequest?> =
        firestore.watchLocationRequest(targetUid, requestId).map { it?.toDomain() }

    override fun watchPendingRequests(uid: String): Flow<List<LocationRequest>> =
        firestore.watchPendingLocationRequests(uid).map { list -> list.map { it.toDomain() } }

    override suspend fun answerRequest(uid: String, requestId: String, status: String, answer: String) =
        firestore.answerLocationRequest(uid, requestId, status, answer)
}
