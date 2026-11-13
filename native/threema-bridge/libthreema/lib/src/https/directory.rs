//! Directory endpoint.
use serde::{Deserialize, Serialize};
use serde_repr::Deserialize_repr;

use super::endpoint::{HttpsEndpointError, TIMEOUT, https_headers_with_authentication};
use crate::{
    common::{
        ChatServerGroup, ClientInfo, FeatureMask, ThreemaId,
        config::{DirectoryServerBaseUrl, Flavor, WorkContext},
        keys::{ClientKey, PublicKey},
    },
    crypto::digest::Mac as _,
    https::{HttpsHeadersBuilder, HttpsMethod, HttpsRequest, HttpsResponse, HttpsResult},
    model::contact::ContactInit,
    protobuf::{self, d2d_sync::contact as protobuf_contact},
    utils::{serde::base64, time::utc_now_ms},
};

#[derive(Debug, Serialize)]
struct WorkCredentials<'creds> {
    #[serde(rename = "licenseUsername")]
    username: &'creds str,

    #[serde(rename = "licensePassword")]
    password: &'creds str,
}
impl<'creds> From<&'creds crate::common::config::WorkCredentials> for WorkCredentials<'creds> {
    fn from(credentials: &'creds crate::common::config::WorkCredentials) -> Self {
        Self {
            username: &credentials.username,
            password: &credentials.password,
        }
    }
}

#[inline]
fn https_headers(mode: &Flavor) -> HttpsHeadersBuilder {
    match mode {
        Flavor::Consumer => HttpsHeadersBuilder::default(),
        Flavor::Work(context) => https_headers_with_authentication(context),
    }
}

fn handle_status<TStatusFn: FnOnce(u16) -> Option<HttpsEndpointError>>(
    result: HttpsResult,
    unexpected_status_map_fn: TStatusFn,
) -> Result<HttpsResponse, HttpsEndpointError> {
    let response = result?;
    match response.status {
        200 | 204 => Ok(response),
        401 => Err(HttpsEndpointError::InvalidCredentials),
        429 => Err(HttpsEndpointError::RateLimitExceeded),
        status => {
            Err(unexpected_status_map_fn(status).unwrap_or(HttpsEndpointError::UnexpectedStatus(status)))
        },
    }
}

#[derive(Deserialize)]
#[serde(untagged)]
enum AwkwardResponse {
    Error {
        #[serde(rename = "success")]
        success: bool,
        #[serde(rename = "error")]
        error: String,
    },
    Success {
        #[serde(rename = "success")]
        success: bool,
    },
}

fn handle_status_and_awkward_response<TStatusFn: FnOnce(u16) -> Option<HttpsEndpointError>>(
    result: HttpsResult,
    unexpected_status_map_fn: TStatusFn,
) -> Result<HttpsResponse, HttpsEndpointError> {
    let response = handle_status(result, unexpected_status_map_fn)?;

    // Handle the super-awkward response with the `success` field
    if response.status != 200 {
        return Err(HttpsEndpointError::UnexpectedStatus(response.status));
    }
    match serde_json::from_slice::<AwkwardResponse>(&response.body)? {
        AwkwardResponse::Error {
            success: _success,
            error,
        } => Err(HttpsEndpointError::CustomPossiblyLocalizedError(error)),
        AwkwardResponse::Success { success } => {
            if success {
                Ok(response)
            } else {
                Err(HttpsEndpointError::CustomPossiblyLocalizedError(
                    "Server is totally stoned".to_owned(),
                ))
            }
        },
    }
}

#[derive(Deserialize)]
struct AuthenticationChallenge {
    #[serde(rename = "tokenRespKeyPub", with = "base64::fixed_length")]
    public_key: [u8; PublicKey::LENGTH],

    #[serde(rename = "token", with = "base64::variable_length")]
    challenge: Vec<u8>,
}

#[derive(Serialize)]
pub struct AuthenticationChallengeResponse {
    #[serde(rename = "token", with = "base64::variable_length")]
    challenge: Vec<u8>,

    #[serde(rename = "response", with = "base64::fixed_length")]
    response: [u8; PublicKey::LENGTH],
}

/// Process the result and solve the authentication challenge.
pub fn handle_authentication_challenge(
    client_key: &ClientKey,
    result: HttpsResult,
) -> Result<AuthenticationChallengeResponse, HttpsEndpointError> {
    let response = handle_status(result, |_| None)?;
    let challenge: AuthenticationChallenge = serde_json::from_slice(&response.body)?;
    let response = AuthenticationChallengeResponse {
        challenge: challenge.challenge.clone(),
        response: client_key
            .derive_directory_authentication_key(&PublicKey::from(challenge.public_key))
            .0
            .chain_update(&challenge.challenge)
            .finalize()
            .into_bytes()
            .into(),
    };
    Ok(response)
}

#[derive(Serialize)]
struct SetFeatureMaskPhase1Request {
    identity: ThreemaId,
    #[serde(rename = "featureMask")]
    feature_mask: u64,
}

#[derive(Serialize)]
struct SetFeatureMaskPhase2Request {
    identity: ThreemaId,
    #[serde(rename = "featureMask")]
    feature_mask: u64,
    #[serde(flatten)]
    authentication: AuthenticationChallengeResponse,
}

pub fn set_feature_mask_phase1_request(
    client_info: &ClientInfo,
    directory_server_url: &DirectoryServerBaseUrl,
    mode: &Flavor,
    identity: ThreemaId,
    feature_mask: u64,
) -> HttpsRequest {
    HttpsRequest {
        timeout: TIMEOUT,
        url: directory_server_url.set_feature_mask_path(),
        method: HttpsMethod::Post,
        headers: https_headers(mode).accept("application/json").build(client_info),
        body: serde_json::to_vec(&SetFeatureMaskPhase1Request {
            identity,
            feature_mask,
        })
        .expect("Failed to create set feature mask phase 1 request body"),
    }
}

pub fn set_feature_mask_phase2_request(
    client_info: &ClientInfo,
    directory_server_url: &DirectoryServerBaseUrl,
    mode: &Flavor,
    identity: ThreemaId,
    feature_mask: u64,
    authentication: AuthenticationChallengeResponse,
) -> HttpsRequest {
    HttpsRequest {
        timeout: TIMEOUT,
        url: directory_server_url.set_feature_mask_path(),
        method: HttpsMethod::Post,
        headers: https_headers(mode).accept("application/json").build(client_info),
        body: serde_json::to_vec(&SetFeatureMaskPhase2Request {
            identity,
            feature_mask,
            authentication,
        })
        .expect("Failed to create set feature mask phase 2 request body"),
    }
}

pub fn handle_set_feature_mask_phase2_result(result: HttpsResult) -> Result<(), HttpsEndpointError> {
    let _ = handle_status_and_awkward_response(result, |_| None)?;
    Ok(())
}

// ---------------------------------------------------------------------------------------------
// LOCAL ADDITION — creating an identity with a Threema *serial* license.
//
// Upstream models exactly two flavors for identity creation: `Consumer`, which sends no
// credentials at all, and `Work`, which sends a licenseUsername/licensePassword pair. Neither
// covers a serial ("shop") license, which authenticates with a single `licenseKey` field — the
// path the Threema Android app takes in its `serial` build flavor.
//
// If a libthreema sync ever drops this block, identity creation stops compiling rather than
// silently misbehaving, which is the failure mode we want.
// ---------------------------------------------------------------------------------------------

#[derive(Serialize)]
struct CreateIdentityPhase1Request {
    #[serde(rename = "publicKey", with = "base64::fixed_length")]
    public_key: [u8; PublicKey::LENGTH],
}

#[derive(Serialize)]
struct CreateIdentityWithLicensePhase2Request<'body> {
    #[serde(rename = "publicKey", with = "base64::fixed_length")]
    public_key: [u8; PublicKey::LENGTH],

    #[serde(rename = "deviceId")]
    device_id: &'body str,

    #[serde(rename = "licenseKey")]
    license_key: &'body str,

    #[serde(flatten)]
    authentication: AuthenticationChallengeResponse,
}

/// A freshly created identity, as assigned by the directory server.
#[derive(Deserialize)]
pub struct CreatedIdentity {
    /// The assigned Threema ID.
    #[serde(rename = "identity")]
    pub identity: ThreemaId,

    /// The chat server group the identity was assigned to.
    #[serde(rename = "serverGroup")]
    pub server_group: ChatServerGroup,
}

/// Ask the directory server for an authentication challenge to create a new identity.
///
/// Feed the result to [`handle_authentication_challenge`], then
/// [`create_identity_with_license_phase2_request`].
pub fn create_identity_phase1_request(
    client_info: &ClientInfo,
    directory_server_url: &DirectoryServerBaseUrl,
    public_key: &PublicKey,
) -> HttpsRequest {
    HttpsRequest {
        timeout: TIMEOUT,
        url: directory_server_url.create_identity_path(),
        method: HttpsMethod::Post,
        headers: https_headers(&Flavor::Consumer)
            .accept("application/json")
            .build(client_info),
        body: serde_json::to_vec(&CreateIdentityPhase1Request {
            public_key: public_key.0.to_bytes(),
        })
        .expect("Failed to create identity creation phase 1 request body"),
    }
}

/// Claim a new identity against a serial license, answering the challenge from phase 1.
pub fn create_identity_with_license_phase2_request(
    client_info: &ClientInfo,
    directory_server_url: &DirectoryServerBaseUrl,
    public_key: &PublicKey,
    device_id: &str,
    license_key: &str,
    authentication: AuthenticationChallengeResponse,
) -> HttpsRequest {
    HttpsRequest {
        timeout: TIMEOUT,
        url: directory_server_url.create_identity_path(),
        method: HttpsMethod::Post,
        headers: https_headers(&Flavor::Consumer)
            .accept("application/json")
            .build(client_info),
        body: serde_json::to_vec(&CreateIdentityWithLicensePhase2Request {
            public_key: public_key.0.to_bytes(),
            device_id,
            license_key,
            authentication,
        })
        .expect("Failed to create identity creation phase 2 request body"),
    }
}

/// Process the result of claiming a new identity.
///
/// Named apart from the upstream `handle_create_identity_result` on purpose — that one is part
/// of the task-based `Consumer`/`Work` flow and still exists.
pub fn handle_create_identity_with_license_result(
    result: HttpsResult,
) -> Result<CreatedIdentity, HttpsEndpointError> {
    let response = handle_status_and_awkward_response(result, |_| None)?;
    Ok(serde_json::from_slice(&response.body)?)
}

#[derive(Serialize)]
struct ObtainTurnServersPhase1Request<'body> {
    identity: ThreemaId,
    #[serde(rename = "type")]
    turn_type: &'body str,
}

#[derive(Serialize)]
struct ObtainTurnServersPhase2Request<'body> {
    identity: ThreemaId,
    #[serde(rename = "type")]
    turn_type: &'body str,
    #[serde(flatten)]
    authentication: AuthenticationChallengeResponse,
}

#[derive(Deserialize, Serialize, Debug, Clone)]
pub struct TurnServerResponse {
    pub success: bool,
    #[serde(default, rename = "turnUrls")]
    pub turn_urls: Vec<String>,
    #[serde(default, rename = "turnUrlsDualStack")]
    pub turn_urls_dual_stack: Vec<String>,
    #[serde(default, rename = "turnUsername")]
    pub turn_username: String,
    #[serde(default, rename = "turnPassword")]
    pub turn_password: String,
    #[serde(default)]
    pub expiration: u64,
    #[serde(default)]
    pub error: Option<String>,
}

pub fn obtain_turn_servers_phase1_request(
    client_info: &ClientInfo,
    directory_server_url: &DirectoryServerBaseUrl,
    mode: &Flavor,
    identity: ThreemaId,
    turn_type: &str,
) -> HttpsRequest {
    HttpsRequest {
        timeout: TIMEOUT,
        url: directory_server_url.turn_cred_path(),
        method: HttpsMethod::Post,
        headers: https_headers(mode).accept("application/json").build(client_info),
        body: serde_json::to_vec(&ObtainTurnServersPhase1Request {
            identity,
            turn_type,
        })
        .expect("Failed to create obtain turn servers phase 1 request body"),
    }
}

pub fn obtain_turn_servers_phase2_request(
    client_info: &ClientInfo,
    directory_server_url: &DirectoryServerBaseUrl,
    mode: &Flavor,
    identity: ThreemaId,
    turn_type: &str,
    authentication: AuthenticationChallengeResponse,
) -> HttpsRequest {
    HttpsRequest {
        timeout: TIMEOUT,
        url: directory_server_url.turn_cred_path(),
        method: HttpsMethod::Post,
        headers: https_headers(mode).accept("application/json").build(client_info),
        body: serde_json::to_vec(&ObtainTurnServersPhase2Request {
            identity,
            turn_type,
            authentication,
        })
        .expect("Failed to create obtain turn servers phase 2 request body"),
    }
}

pub fn handle_obtain_turn_servers_phase2_result(result: HttpsResult) -> Result<TurnServerResponse, HttpsEndpointError> {
    let response = handle_status(result, |_| None)?;
    let parsed: TurnServerResponse = serde_json::from_slice(&response.body)?;
    Ok(parsed)
}


#[derive(Serialize)]
struct IdentitiesRequest<'request> {
    identities: &'request [ThreemaId],
}

/// Request identity properties tied to a set of identities.
pub fn request_identities(
    client_info: &ClientInfo,
    directory_server_url: &DirectoryServerBaseUrl,
    mode: &Flavor,
    identities: &[ThreemaId],
) -> HttpsRequest {
    HttpsRequest {
        timeout: TIMEOUT,
        url: directory_server_url.request_identities_path(),
        method: HttpsMethod::Post,
        headers: https_headers(mode).accept("application/json").build(client_info),
        body: serde_json::to_vec(&IdentitiesRequest { identities })
            .expect("Failed to create directory identities request body"),
    }
}

#[derive(Deserialize_repr)]
#[repr(u8)]
enum IdentityType {
    Regular = 0,
    Work = 1,
}
impl From<IdentityType> for protobuf_contact::IdentityType {
    fn from(r#type: IdentityType) -> Self {
        match r#type {
            IdentityType::Regular => protobuf_contact::IdentityType::Regular,
            IdentityType::Work => protobuf_contact::IdentityType::Work,
        }
    }
}

#[derive(Default, Deserialize_repr)]
#[repr(u8)]
enum ActivityState {
    #[default]
    Active = 0,
    Inactive = 1,
}
impl From<ActivityState> for protobuf_contact::ActivityState {
    fn from(state: ActivityState) -> Self {
        match state {
            ActivityState::Active => protobuf_contact::ActivityState::Active,
            ActivityState::Inactive => protobuf_contact::ActivityState::Inactive,
        }
    }
}

#[derive(Deserialize)]
struct ValidIdentity {
    #[serde(rename = "identity")]
    identity: ThreemaId,

    #[serde(rename = "type")]
    identity_type: IdentityType,

    #[serde(rename = "publicKey", with = "base64::fixed_length")]
    public_key: [u8; PublicKey::LENGTH],

    // Note: OnPrem directory omits this parameter for some reason, so we need a default
    #[serde(default, rename = "state")]
    activity_state: ActivityState,

    #[serde(rename = "featureMask")]
    feature_mask: u64,
}
impl From<ValidIdentity> for ContactInit {
    fn from(entry: ValidIdentity) -> Self {
        ContactInit {
            identity: entry.identity,
            public_key: PublicKey::from(entry.public_key),
            created_at: utc_now_ms(),
            first_name: None,
            last_name: None,
            nickname: None,
            verification_level: protobuf_contact::VerificationLevel::Unverified,
            work_verification_level: protobuf_contact::WorkVerificationLevel::None,
            identity_type: entry.identity_type.into(),
            acquaintance_level: protobuf_contact::AcquaintanceLevel::GroupOrDeleted,
            activity_state: entry.activity_state.into(),
            feature_mask: FeatureMask(entry.feature_mask),
            sync_state: protobuf_contact::SyncState::Initial,
            read_receipt_policy_override: None,
            typing_indicator_policy_override: None,
            notification_trigger_policy_override: None,
            notification_sound_policy_override: None,
            conversation_category: protobuf::d2d_sync::ConversationCategory::Default,
            conversation_visibility: protobuf::d2d_sync::ConversationVisibility::Normal,
        }
    }
}

#[derive(Deserialize)]
struct IdentitiesResponse {
    #[serde(rename = "identities")]
    identities: Vec<ValidIdentity>,
}

/// Process the identities result and map it to all valid identities.
///
/// IMPORTANT: Identities that do not exist or have already been revoked will not be included!
pub fn handle_identities_result(result: HttpsResult) -> Result<Vec<ContactInit>, HttpsEndpointError> {
    let response = handle_status(result, |_| None)?;
    let IdentitiesResponse { identities } = serde_json::from_slice(&response.body)?;
    Ok(identities.into_iter().map(ContactInit::from).collect())
}

#[derive(Serialize)]
struct CreateIdentityRequest {
    #[serde(rename = "publicKey", with = "base64::fixed_length")]
    public_key: [u8; PublicKey::LENGTH],
}

/// Request an authentication challenge to create an identity.
pub(crate) fn create_identity_authentication_request(
    client_info: &ClientInfo,
    directory_server_url: &DirectoryServerBaseUrl,
    mode: &Flavor,
    public_key: PublicKey,
) -> HttpsRequest {
    HttpsRequest {
        timeout: TIMEOUT,
        url: directory_server_url.create_identity_path(),
        method: HttpsMethod::Post,
        headers: https_headers(mode).accept("application/json").build(client_info),
        body: serde_json::to_vec(&CreateIdentityRequest {
            public_key: public_key.0.to_bytes(),
        })
        .expect("Failed to create directory identity creation challenge request body"),
    }
}

#[derive(Serialize)]
struct CreateIdentityAuthenticatedRequest<'creds> {
    #[serde(flatten)]
    request: CreateIdentityRequest,

    #[serde(flatten)]
    authentication: AuthenticationChallengeResponse,

    #[serde(flatten)]
    credentials: Option<WorkCredentials<'creds>>,
}

/// Create an identity.
pub(crate) fn create_identity_request(
    client_info: &ClientInfo,
    directory_server_url: &DirectoryServerBaseUrl,
    mode: &Flavor,
    authentication: AuthenticationChallengeResponse,
    public_key: PublicKey,
) -> HttpsRequest {
    let credentials = match mode {
        Flavor::Work(work_context) => Some(WorkCredentials::from(&work_context.credentials)),
        Flavor::Consumer => None,
    };
    HttpsRequest {
        timeout: TIMEOUT,
        url: directory_server_url.create_identity_path(),
        method: HttpsMethod::Post,
        headers: https_headers(mode).accept("application/json").build(client_info),
        body: serde_json::to_vec(&CreateIdentityAuthenticatedRequest {
            request: CreateIdentityRequest {
                public_key: public_key.0.to_bytes(),
            },
            authentication,
            credentials,
        })
        .expect("Failed to create directory identity creation request body"),
    }
}

/// Response for a newly created identity.
#[derive(Deserialize)]
pub(crate) struct CreateIdentityResponse {
    #[serde(rename = "identity")]
    pub(crate) identity: ThreemaId,

    #[serde(rename = "serverGroup")]
    pub(crate) server_group: ChatServerGroup,
}

/// Process the result after attempting to create an identity.
pub(crate) fn handle_create_identity_result(
    result: HttpsResult,
) -> Result<CreateIdentityResponse, HttpsEndpointError> {
    let response = handle_status_and_awkward_response(result, |_| None)?;
    Ok(serde_json::from_slice(&response.body)?)
}

#[derive(Serialize)]
struct UpdateWorkPropertiesRequest<'body> {
    #[serde(flatten)]
    credentials: WorkCredentials<'body>,

    #[serde(rename = "identity")]
    identity: ThreemaId,

    #[serde(rename = "version")]
    version: &'body str,
}

/// Request an authentication challenge to update work properties.
///
/// Note: Technically, this is a part that the work directory should do but historically it is part of the
/// directory. So, we're reflecting that... for now.
pub(crate) fn update_work_properties_authentication_request(
    client_info: &ClientInfo,
    directory_server_url: &DirectoryServerBaseUrl,
    work_context: &WorkContext,
    identity: ThreemaId,
) -> HttpsRequest {
    HttpsRequest {
        timeout: TIMEOUT,
        url: directory_server_url.update_work_properties_path(),
        method: HttpsMethod::Post,
        headers: https_headers_with_authentication(work_context)
            .accept("application/json")
            .build(client_info),
        body: serde_json::to_vec(&UpdateWorkPropertiesRequest {
            credentials: (&work_context.credentials).into(),
            identity,
            version: &client_info.to_semicolon_separated(),
        })
        .expect("Failed to create update work properties challenge request body"),
    }
}

#[derive(Serialize)]
struct UpdateWorkPropertiesAuthenticatedRequest<'body> {
    #[serde(flatten)]
    request: UpdateWorkPropertiesRequest<'body>,

    #[serde(flatten)]
    authentication: AuthenticationChallengeResponse,
}

/// Update work properties.
///
/// Note: Technically, this is a part that the work directory should do but historically it is part of the
/// directory. So, we're reflecting that... for now.
pub(crate) fn update_work_properties_request(
    client_info: &ClientInfo,
    directory_server_url: &DirectoryServerBaseUrl,
    work_context: &WorkContext,
    identity: ThreemaId,
    authentication: AuthenticationChallengeResponse,
) -> HttpsRequest {
    HttpsRequest {
        timeout: TIMEOUT,
        url: directory_server_url.update_work_properties_path(),
        method: HttpsMethod::Post,
        headers: https_headers_with_authentication(work_context)
            .accept("application/json")
            .build(client_info),
        body: serde_json::to_vec(&UpdateWorkPropertiesAuthenticatedRequest {
            request: UpdateWorkPropertiesRequest {
                credentials: (&work_context.credentials).into(),
                identity,
                version: &client_info.to_semicolon_separated(),
            },
            authentication,
        })
        .expect("Failed to create update work properties request body"),
    }
}

/// Process the result after attempting to update work properties.
pub(crate) fn handle_update_work_properties_result(result: HttpsResult) -> Result<(), HttpsEndpointError> {
    let _ = handle_status_and_awkward_response(result, |_| None)?;
    Ok(())
}

// ---------------------------------------------------------------------------------------------
// LOCAL ADDITION — contact matching (address-book sync).
//
// Upstream libthreema has no equivalent of the Threema app's address-book sync: it can turn an
// identity you already know into a public key (`request_identities`), but nothing maps a phone
// number or e-mail address to an identity. That mapping is the only way a contact in an ordinary
// Android address book acquires a Threema ID, so it is ported here from the Threema Android app
// (`domain/.../api/APIConnector.java`, `matchIdentities`).
//
// Two requests, in this order:
//
//  1. `identity/match_token` — the same two-phase challenge as feature-mask and identity creation,
//     answered with [`handle_authentication_challenge`]. Yields a match token, which the server
//     will happily keep accepting until it doesn't; callers are expected to re-fetch on failure.
//  2. `identity/match` — the token plus base64 HMAC-SHA256 hashes of the normalised addresses.
//
// The caller does the hashing, because it is the caller that knows which contact each hash came
// from and the server response identifies matches by hash alone.
//
// Note that the response carries `publicKey`, so a matched contact is immediately ready to be
// messaged without a second directory round-trip.
// ---------------------------------------------------------------------------------------------

#[derive(Serialize)]
struct MatchTokenPhase1Request {
    identity: ThreemaId,
}

#[derive(Serialize)]
struct MatchTokenPhase2Request {
    identity: ThreemaId,
    #[serde(flatten)]
    authentication: AuthenticationChallengeResponse,
}

#[derive(Deserialize)]
struct MatchTokenPhase2Response {
    #[serde(rename = "matchToken")]
    match_token: String,
}

/// Ask the directory server for an authentication challenge to obtain a match token.
///
/// Feed the result to [`handle_authentication_challenge`], then [`match_token_phase2_request`].
pub fn match_token_phase1_request(
    client_info: &ClientInfo,
    directory_server_url: &DirectoryServerBaseUrl,
    mode: &Flavor,
    identity: ThreemaId,
) -> HttpsRequest {
    HttpsRequest {
        timeout: TIMEOUT,
        url: directory_server_url.match_token_path(),
        method: HttpsMethod::Post,
        headers: https_headers(mode).accept("application/json").build(client_info),
        body: serde_json::to_vec(&MatchTokenPhase1Request { identity })
            .expect("Failed to create match token phase 1 request body"),
    }
}

/// Claim a match token, answering the challenge from phase 1.
pub fn match_token_phase2_request(
    client_info: &ClientInfo,
    directory_server_url: &DirectoryServerBaseUrl,
    mode: &Flavor,
    identity: ThreemaId,
    authentication: AuthenticationChallengeResponse,
) -> HttpsRequest {
    HttpsRequest {
        timeout: TIMEOUT,
        url: directory_server_url.match_token_path(),
        method: HttpsMethod::Post,
        headers: https_headers(mode).accept("application/json").build(client_info),
        body: serde_json::to_vec(&MatchTokenPhase2Request {
            identity,
            authentication,
        })
        .expect("Failed to create match token phase 2 request body"),
    }
}

/// Process the result and return the match token.
pub fn handle_match_token_phase2_result(result: HttpsResult) -> Result<String, HttpsEndpointError> {
    let response = handle_status_and_awkward_response(result, |_| None)?;
    let response: MatchTokenPhase2Response = serde_json::from_slice(&response.body)?;
    Ok(response.match_token)
}

/// One identity the directory server recognised, and the hash that found it.
#[derive(Debug, Clone, Deserialize)]
pub struct MatchedIdentity {
    pub identity: ThreemaId,

    #[serde(rename = "publicKey", with = "base64::fixed_length")]
    pub public_key: [u8; PublicKey::LENGTH],

    /// Base64, exactly as the caller submitted it, or absent if the e-mail hash matched instead.
    #[serde(rename = "mobileNoHash", default)]
    pub mobile_no_hash: Option<String>,

    /// Base64, exactly as the caller submitted it, or absent if the phone hash matched instead.
    #[serde(rename = "emailHash", default)]
    pub email_hash: Option<String>,
}

#[derive(Serialize)]
struct MatchIdentitiesRequest<'req> {
    #[serde(rename = "matchToken")]
    match_token: &'req str,

    #[serde(rename = "emailHashes")]
    email_hashes: &'req [String],

    #[serde(rename = "mobileNoHashes")]
    mobile_no_hashes: &'req [String],
}

#[derive(Deserialize)]
struct MatchIdentitiesResponse {
    identities: Vec<MatchedIdentity>,
}

/// Ask the directory server which of these hashed addresses belong to a Threema identity.
///
/// Hashes are base64 HMAC-SHA256 over the normalised address; see the app-side hashing for the
/// keys and the normalisation rules.
pub fn match_identities_request(
    client_info: &ClientInfo,
    directory_server_url: &DirectoryServerBaseUrl,
    mode: &Flavor,
    match_token: &str,
    email_hashes: &[String],
    mobile_no_hashes: &[String],
) -> HttpsRequest {
    HttpsRequest {
        timeout: TIMEOUT,
        url: directory_server_url.match_identities_path(),
        method: HttpsMethod::Post,
        headers: https_headers(mode).accept("application/json").build(client_info),
        body: serde_json::to_vec(&MatchIdentitiesRequest {
            match_token,
            email_hashes,
            mobile_no_hashes,
        })
        .expect("Failed to create match identities request body"),
    }
}

/// Process the result of a contact match.
pub fn handle_match_identities_result(
    result: HttpsResult,
) -> Result<Vec<MatchedIdentity>, HttpsEndpointError> {
    let response = handle_status(result, |_| None)?;
    let response: MatchIdentitiesResponse = serde_json::from_slice(&response.body)?;
    Ok(response.identities)
}
