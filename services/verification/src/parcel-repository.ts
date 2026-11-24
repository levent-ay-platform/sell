import type {SqlPool} from './postgres-evidence-repository.ts';

interface Edge {label:string; lengthM:string}
interface Boundary {
  id:string;
  method:string;
  geometry:object;
  titleReference:string|null;
  plotReference:string|null;
  edges:Edge[];
}
interface DocumentCard {id:string; kind:string; mime:string}
export interface PilotParcelMatch {
  identityHashes:string[];
  documentHashes:string[];
  latitude:number;
  longitude:number;
  titleReference:string;
  plotReference:string;
}
interface PublicParcelProjection {
  boundaries:Boundary[];
  documents:DocumentCard[];
  verifiedParcel:boolean;
}
export interface ApprovedRendition {storageObjectId:string; mime:string; sha256:string}
interface ParcelCard {id:string; titleReference:string|null; plotReference:string|null}

interface OwnerGeometry extends Omit<Boundary,'geometry'|'edges'> {
  edges:(Edge & {ordinal:number})[];
  statedAreaM2:string|null;
  parcelId:string;
  sourceEvidenceId:string|null;
  geometry:object|null;
  localOutline:object|null;
  localPage:number|null;
  localUnit:string|null;
}
export type ParcelCandidate = {
  parcelId:string|null;
  sourceEvidenceId:string|null;
  titleReference:string|null;
  plotReference:string|null;
  method:'DOCUMENT_ONLY'|'OWNER_TRACED_ESTIMATE';
  localPage:number|null;
  localUnit:'PIXEL'|'PAGE_POINT'|'LOCAL_METRE'|null;
  geometryJson:string;
  statedAreaM2:string|null;
  edges:readonly {ordinal:number; lengthM:string; sourcePage:number; label:string|null}[];
};
export type ParcelDecision =
  | {role:'reviewer'; subject:'geometry'; decision:'APPROVED'|'CHANGES_REQUESTED'|'REJECTED'|'REVOKED'; includeReferences:boolean}
  | {role:'reviewer'; subject:'document'; decision:'APPROVED'|'REDACTION_REQUIRED'|'PRIVATE_ONLY'|'REVOKED'; redactionChecked:boolean; rightsCleared:boolean}
  | {role:'owner'; subject:'geometry'|'document'; decision:'GRANTED'|'REVOKED'};

const publicListing = `
SELECT coalesce(boundaries.items,'[]'::jsonb) boundaries,coalesce(documents.items,'[]'::jsonb) documents,
 (l.item_count=1 AND EXISTS (
   SELECT 1 FROM sell.listing_items i JOIN sell.properties p ON p.id=i.property_id AND p.kind='LAND'
   WHERE i.listing_id=l.id
     AND EXISTS (
       SELECT 1 FROM sell.evidence_upload_receipts r
       WHERE r.case_id=i.verification_case_id AND r.evidence_kind='IDENTITY'
         AND (SELECT c.decision FROM sell.evidence_consent_events c
           WHERE c.case_id=r.case_id AND c.person_user_id=r.uploader_user_id
           ORDER BY c.id DESC LIMIT 1)='GRANTED'
     )
     AND EXISTS (
       SELECT 1 FROM sell.evidence_upload_receipts r WHERE r.case_id=i.verification_case_id
         AND r.evidence_kind IN ('OWNERSHIP_CERTIFICATE','SHARED_OWNERSHIP_CERTIFICATE','TITLE_EXTRACT')
         AND (SELECT c.decision FROM sell.evidence_consent_events c
           WHERE c.case_id=r.case_id AND c.person_user_id=r.uploader_user_id
           ORDER BY c.id DESC LIMIT 1)='GRANTED'
     )
     AND EXISTS (
       SELECT 1 FROM sell.public_listing_parcel_boundaries b WHERE b.listing_id=l.id
         AND b.method IN ('ATLAS_OFFICIAL_BOUNDARY','MALAK_DOC_GEOREFERENCED')
     )
 )) AS "verifiedParcel"
FROM sell.public_listing_cards l
LEFT JOIN LATERAL (
 SELECT jsonb_agg(row_to_json(b)) items FROM (
  SELECT p.boundary_id::text id,p.method,p.geometry,p.title_reference "titleReference",p.plot_reference "plotReference",
   coalesce((
    SELECT jsonb_agg(jsonb_build_object('label',coalesce(e.edge_label,e.ordinal::text),'lengthM',
     coalesce(e.stated_length_m,e.computed_length_m)::text) ORDER BY e.ordinal)
    FROM sell.parcel_edge_measurements e WHERE e.geometry_version_id=p.boundary_id
   ),'[]'::jsonb) edges
  FROM sell.public_listing_parcel_boundaries p
  WHERE p.listing_id=l.id AND ($2::text[] IS NULL OR EXISTS (
    SELECT 1 FROM sell.parcel_geometry_versions g WHERE g.id=p.boundary_id
      AND g.method IN ('ATLAS_OFFICIAL_BOUNDARY','MALAK_DOC_GEOREFERENCED')
      AND upper(btrim(g.title_reference))=$6::text AND upper(btrim(g.plot_reference))=$7::text
      AND ST_DWithin(g.geometry_4326::geography,ST_SetSRID(ST_MakePoint($5::float8,$4::float8),4326)::geography,1000)
  )) ORDER BY p.boundary_id LIMIT 20
 ) b
) boundaries ON true
LEFT JOIN LATERAL (
 SELECT jsonb_agg(row_to_json(d)) items FROM (
  SELECT p.document_id::text id,p.document_kind kind,p.rendition_mime mime
  FROM sell.public_listing_parcel_documents p WHERE p.listing_id=l.id
  ORDER BY p.reviewed_at DESC,p.document_id LIMIT 50
 ) d
) documents ON true
WHERE l.id=$1::uuid
AND ($2::text[] IS NULL OR EXISTS (
  SELECT 1 FROM sell.listing_items i
  JOIN sell.public_listing_parcel_boundaries b ON b.listing_id=i.listing_id
  JOIN sell.parcel_geometry_versions g ON g.id=b.boundary_id
    AND (g.property_revision_id,g.case_id)=(i.property_revision_id,i.verification_case_id)
  WHERE i.listing_id=l.id
    AND b.method IN ('ATLAS_OFFICIAL_BOUNDARY','MALAK_DOC_GEOREFERENCED')
    AND upper(btrim(g.title_reference))=$6::text AND upper(btrim(g.plot_reference))=$7::text
    AND ST_DWithin(g.geometry_4326::geography,ST_SetSRID(ST_MakePoint($5::float8,$4::float8),4326)::geography,1000)
    AND EXISTS (SELECT 1 FROM sell.evidence_upload_receipts r WHERE r.case_id=i.verification_case_id
      AND r.evidence_kind='IDENTITY' AND r.sha256=ANY($2::text[])
      AND (SELECT c.decision FROM sell.evidence_consent_events c
        WHERE c.case_id=r.case_id AND c.person_user_id=r.uploader_user_id ORDER BY c.id DESC LIMIT 1)='GRANTED')
    AND EXISTS (SELECT 1 FROM sell.evidence_upload_receipts r WHERE r.case_id=i.verification_case_id
      AND r.evidence_kind IN ('OWNERSHIP_CERTIFICATE','SHARED_OWNERSHIP_CERTIFICATE','TITLE_EXTRACT')
      AND r.sha256=ANY($3::text[])
      AND (SELECT c.decision FROM sell.evidence_consent_events c
        WHERE c.case_id=r.case_id AND c.person_user_id=r.uploader_user_id ORDER BY c.id DESC LIMIT 1)='GRANTED')
))`;

const approvedDocument = `
SELECT d.storage_object_id::text "storageObjectId",p.rendition_mime mime,p.rendition_sha256 sha256
FROM sell.public_listing_parcel_documents p
JOIN sell.parcel_document_versions d ON d.id=p.document_id
WHERE p.listing_id=$1::uuid AND p.document_id=$2::uuid
LIMIT 1`;

const ownerMembership = `EXISTS (
 SELECT 1 FROM sell.party_memberships m
 WHERE m.party_id=claim.claimant_party_id AND m.user_id=$2::uuid AND m.capacity IN ('OWNER','REPRESENTATIVE')
)`;
const ownerScope = `
 SELECT c.id, r.property_id, c.property_revision_id, c.status
 FROM sell.verification_cases c
 JOIN sell.ownership_claims claim ON claim.id=c.ownership_claim_id
 JOIN sell.property_revisions r ON r.id=c.property_revision_id AND r.property_id=claim.property_id
 JOIN sell.properties p ON p.id=r.property_id AND p.kind='LAND'
 WHERE c.id=$1::uuid AND ${ownerMembership}`;

const privateCaseRead = (scopeSql:string) => `
SELECT coalesce((SELECT jsonb_agg(row_to_json(p)) FROM (
 SELECT id::text,registry_reference "titleReference",subdivision_plot_number "plotReference"
 FROM sell.parcels WHERE property_id=scope.property_id ORDER BY id LIMIT 50
) p),'[]'::jsonb) parcels,
coalesce((SELECT jsonb_agg(row_to_json(e)) FROM (
 SELECT evidence_id::text id,evidence_kind kind FROM sell.evidence_upload_receipts
 WHERE case_id=scope.id AND evidence_kind IN
 ('CADASTRAL_PLAN','TITLE_EXTRACT','OWNERSHIP_CERTIFICATE','SHARED_OWNERSHIP_CERTIFICATE',
  'SALE_DEED','TRANSFER_CARD','POSSESSION_REPORT','OTHER_LEGAL_PROOF') ORDER BY evidence_id LIMIT 100
) e),'[]'::jsonb) evidence,
coalesce((SELECT jsonb_agg(row_to_json(g)) FROM (
 SELECT v.id::text,v.parcel_id::text "parcelId",v.source_evidence_id::text "sourceEvidenceId",v.method,
  ST_AsGeoJSON(v.geometry_4326)::jsonb geometry,
  CASE WHEN v.method='DOCUMENT_ONLY' THEN ST_AsGeoJSON(v.raw_geometry)::jsonb END "localOutline",
  v.stated_area_m2::text "statedAreaM2",v.title_reference "titleReference",v.plot_reference "plotReference",v.local_unit "localUnit",v.local_page "localPage",
  coalesce((SELECT jsonb_agg(jsonb_build_object('ordinal',e.ordinal,'label',coalesce(e.edge_label,e.ordinal::text),'lengthM',
   coalesce(e.stated_length_m,e.computed_length_m)::text) ORDER BY e.ordinal)
   FROM sell.parcel_edge_measurements e WHERE e.geometry_version_id=v.id),'[]'::jsonb) edges
 FROM sell.parcel_geometry_versions v WHERE v.case_id=scope.id ORDER BY v.created_at DESC,v.id LIMIT 50
) g),'[]'::jsonb) geometries
FROM (${scopeSql}) scope`;

export interface AtlasCaseReviewQuery {
  geometryId:string; registryScope:string; localityKey:string;
}
export interface AtlasCaseReviewSummary {
  candidateCount:number; officialSourceCount:number; withGeometryCount:number; truncated:boolean;
  ownershipVerified:false; mayPublish:false;
}
export interface ParcelRepository {
  listing(listingId:string,match?:PilotParcelMatch):Promise<PublicParcelProjection|null>;
  approvedDocument(listingId:string,documentId:string):Promise<ApprovedRendition|null>;
  ownerCase(caseId:string,actor:string,role?:'owner'|'reviewer'):Promise<{parcels:ParcelCard[];evidence:{id:string;kind:string}[];geometries:OwnerGeometry[]}|null>;
  reviewAtlasCandidates?(caseId:string,actor:string,query:AtlasCaseReviewQuery):Promise<AtlasCaseReviewSummary|null>;
  saveOwnerGeometry(caseId:string,actor:string,candidate:ParcelCandidate):Promise<string|null>;
  recordDecision(caseId:string,actor:string,resourceId:string,decision:ParcelDecision):Promise<boolean>;
}

export function createParcelRepository(pool:SqlPool):ParcelRepository {
  return {
    async listing(listingId,match) {
      const {rows} = await pool.query<PublicParcelProjection>(publicListing,[listingId,
        match?.identityHashes??null,match?.documentHashes??null,match?.latitude??null,match?.longitude??null,
        match?.titleReference??null,match?.plotReference??null]);
      return rows[0] ?? null;
    },
    async approvedDocument(listingId,documentId) {
      const {rows} = await pool.query<ApprovedRendition>(approvedDocument,[listingId,documentId]);
      return rows[0] ?? null;
    },
    async ownerCase(caseId,actor,role='owner') {
      const scopeSql=role==='owner' ? ownerScope : ownerScope.replace(ownerMembership,'c.assigned_reviewer_user_id=$2::uuid');
      const {rows} = await pool.query<{parcels:ParcelCard[];evidence:{id:string;kind:string}[];geometries:OwnerGeometry[]}>(
        privateCaseRead(scopeSql),[caseId,actor]);
      return rows[0] ?? null;
    },
    async reviewAtlasCandidates(caseId,actor,query) {
      // Only the persisted document-linked geometry's references can be searched.
      // Scope/locality are explicit reviewer context, never proof of registry identity.
      const {rows:[row]}=await pool.query<{candidateCount:number;officialSourceCount:number;withGeometryCount:number}>(`
        WITH allowed AS (
          SELECT g.title_reference,g.plot_reference
          FROM sell.verification_cases c
          JOIN sell.property_revisions r ON r.id=c.property_revision_id
          JOIN sell.parcel_geometry_versions g ON g.case_id=c.id AND g.property_revision_id=r.id
          WHERE c.id=$1::uuid AND c.assigned_reviewer_user_id=$2::uuid
            AND c.status IN ('PENDING','NEEDS_INFO') AND c.expires_at>now()
            AND g.id=$3::uuid AND g.method='DOCUMENT_ONLY'
            AND g.title_reference IS NOT NULL AND g.plot_reference IS NOT NULL
            AND (g.registry_scope IS NULL OR g.registry_scope=$4)
            AND (g.locality_key IS NULL OR g.locality_key=$5)
            AND NOT EXISTS (SELECT 1 FROM sell.property_revisions newer
              WHERE newer.property_id=r.property_id AND newer.revision_number>r.revision_number)
            AND EXISTS (SELECT 1 FROM sell.evidence_upload_receipts e
              WHERE e.case_id=c.id AND e.evidence_id=g.source_evidence_id
                AND e.evidence_kind IN ('CADASTRAL_PLAN','TITLE_EXTRACT','OWNERSHIP_CERTIFICATE','SHARED_OWNERSHIP_CERTIFICATE')
                AND (SELECT event.decision FROM sell.evidence_consent_events event
                  WHERE event.case_id=c.id AND event.person_user_id=e.uploader_user_id
                  ORDER BY event.id DESC LIMIT 1)='GRANTED')
        )
        SELECT count(m.id)::int "candidateCount",
          count(m.id) FILTER (WHERE m.classification='OFFICIAL_CADASTRAL')::int "officialSourceCount",
          count(m.id) FILTER (WHERE m.geometry_4326 IS NOT NULL)::int "withGeometryCount"
        FROM allowed LEFT JOIN LATERAL (
          SELECT a.id,a.classification,a.geometry_4326
          FROM sell.atlas_parcel_source_records a
          WHERE (a.registry_scope,a.title_reference,a.plot_reference,a.locality_key)=
            ($4,allowed.title_reference,allowed.plot_reference,$5)
          ORDER BY a.id LIMIT 51
        ) m ON true
        GROUP BY allowed.title_reference,allowed.plot_reference
      `,[caseId,actor,query.geometryId,query.registryScope,query.localityKey]);
      if (!row) return null;
      return {candidateCount:row.candidateCount,officialSourceCount:row.officialSourceCount,
        withGeometryCount:row.withGeometryCount,truncated:row.candidateCount===51,
        ownershipVerified:false,mayPublish:false};
    },
    async saveOwnerGeometry(caseId,actor,candidate) {
      const connection = await pool.connect();
      try {
        await connection.query('BEGIN');
        const {rows:[scope]} = await connection.query<{property_id:string;property_revision_id:string}>(
          ownerScope + ` AND c.status IN ('PENDING','NEEDS_INFO')
           AND NOT EXISTS (SELECT 1 FROM sell.property_revisions newer WHERE newer.property_id=r.property_id
             AND newer.revision_number>r.revision_number) FOR UPDATE OF c,p`, [caseId,actor]);
        if (!scope) {
          await connection.query('ROLLBACK');
          return null;
        }
        const {rows:parcels} = await connection.query<{id:string}>(
          'SELECT id::text FROM sell.parcels WHERE property_id=$1::uuid AND ($2::uuid IS NULL OR id=$2) LIMIT 2',
          [scope.property_id,candidate.parcelId]);
        if (parcels.length > 1 || (candidate.parcelId && parcels.length === 0)) {
          await connection.query('ROLLBACK');
          return null;
        }
        const parcelId = parcels[0]?.id ?? (await connection.query<{id:string}>(
          'INSERT INTO sell.parcels(property_id) VALUES($1::uuid) RETURNING id::text',[scope.property_id])).rows[0]?.id;
        if (!parcelId) throw new Error('PARCEL_INSERT_FAILED');
        const {rows:[geometry]} = await connection.query<{id:string}>(`
          INSERT INTO sell.parcel_geometry_versions(parcel_id,property_id,property_revision_id,case_id,
           method,source_evidence_id,title_reference,plot_reference,raw_geometry,local_page,local_unit,stated_area_m2)
          VALUES($1,$2,$3,$4,$5,$6,$7,$8,ST_SetSRID(ST_GeomFromGeoJSON($9::jsonb),$10::integer),$11,$12,$13)
          RETURNING id::text`, [parcelId,scope.property_id,scope.property_revision_id,caseId,
          candidate.method,candidate.sourceEvidenceId,candidate.titleReference,candidate.plotReference,
          candidate.geometryJson,candidate.method==='DOCUMENT_ONLY'?0:4326,candidate.localPage,candidate.localUnit,candidate.statedAreaM2]);
        if (!geometry) throw new Error('GEOMETRY_INSERT_FAILED');
        if (candidate.method==='OWNER_TRACED_ESTIMATE') {
          await connection.query(`
           INSERT INTO sell.parcel_edge_measurements(geometry_version_id,ordinal,computed_length_m)
           SELECT g.id,segment.path[2],ST_Length(segment.geom::geography)
           FROM sell.parcel_geometry_versions g
           CROSS JOIN LATERAL ST_DumpSegments(g.raw_geometry) segment
           WHERE g.id=$1::uuid`, [geometry.id]);
        } else if (candidate.edges.length) {
          await connection.query(`
           INSERT INTO sell.parcel_edge_measurements(geometry_version_id,ordinal,stated_length_m,source_page,edge_label)
           SELECT $1::uuid,e.ordinal,e."lengthM",e."sourcePage",e.label FROM
           jsonb_to_recordset($2::jsonb) AS e(ordinal smallint,"lengthM" numeric,"sourcePage" smallint,label text)`,
           [geometry.id,JSON.stringify(candidate.edges)]);
        }
        await connection.query('COMMIT');
        return geometry.id;
      } catch (error) {
        await connection.query('ROLLBACK').catch(()=>{});
        throw error;
      } finally {
        connection.release();
      }
    },
    async recordDecision(caseId,actor,resourceId,decision) {
      const isGeometry = decision.subject === 'geometry';
      const versionTable = isGeometry ? 'parcel_geometry_versions' : 'parcel_document_versions';
      const idColumn = isGeometry ? 'geometry_version_id' : 'document_version_id';
      let statement:string;
      let evidence:readonly unknown[];
      if (decision.role==='owner') {
        statement = `
         INSERT INTO sell.${isGeometry ? 'parcel_geometry_consent_events' : 'parcel_document_consent_events'}
          (${idColumn},case_id,actor_user_id,decision)
         SELECT version.id,version.case_id,$2::uuid,$4
         FROM sell.${versionTable} version
         JOIN sell.verification_cases c ON c.id=version.case_id
         JOIN sell.ownership_claims claim ON claim.id=c.ownership_claim_id
         WHERE c.id=$1::uuid AND version.id=$3::uuid AND ${ownerMembership}
         RETURNING id`;
        evidence = [];
      } else {
        statement = `
         INSERT INTO sell.${isGeometry ? 'parcel_geometry_review_events' : 'parcel_document_review_events'}
          (${idColumn},case_id,reviewer_user_id,decision,${isGeometry ? 'include_reference_labels' : 'redaction_checked,rights_cleared'})
         SELECT version.id,version.case_id,$2::uuid,$4,${isGeometry ? '$5::boolean' : '$5::boolean,$6::boolean'}
         FROM sell.${versionTable} version
         JOIN sell.verification_cases c ON c.id=version.case_id
         WHERE c.id=$1::uuid AND version.id=$3::uuid AND c.assigned_reviewer_user_id=$2::uuid
         RETURNING id`;
        evidence = decision.subject==='geometry'
          ? [decision.includeReferences] : [decision.redactionChecked,decision.rightsCleared];
      }
      const {rows} = await pool.query(statement,[caseId,actor,resourceId,decision.decision,...evidence]);
      return rows.length === 1;
    },
  };
}
