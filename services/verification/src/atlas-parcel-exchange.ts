import type {SqlPool} from './postgres-evidence-repository.ts';

type Polygon = {
  readonly type: 'Polygon' | 'MultiPolygon';
  readonly coordinates: readonly unknown[];
};

export interface ReviewedAtlasParcel {
  readonly namespace: string;
  readonly sourceId: string;
  readonly layerId: string;
  readonly externalId: string;
  readonly version: string;
  readonly sha256: string;
  readonly registryScope: string | null;
  readonly titleReference: string | null;
  readonly plotReference: string | null;
  readonly localityKey: string | null;
  readonly classification: 'OFFICIAL_CADASTRAL' | 'VERIFIED_PLANNING' | 'PLAN_NOT_GEOREFERENCED' | 'CANDIDATE';
  readonly allowBoundaryRedistribution: boolean;
  readonly geometry4326: Polygon | null;
}

/** Internal adapter for versioned outputs selected by the Atlas source reviewer. */
export async function importReviewedAtlasParcel(
  database: Pick<SqlPool, 'query'>, record: ReviewedAtlasParcel,
): Promise<string> {
  const serialized = record.geometry4326 === null ? null : JSON.stringify(record.geometry4326);
  if (serialized && Buffer.byteLength(serialized) > 1_000_000) throw new Error('ATLAS_GEOMETRY_TOO_LARGE');
  const {rows} = await database.query<{id: string}>(`
    SELECT sell.register_atlas_parcel_record($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,
      CASE WHEN $13::text IS NULL THEN NULL ELSE ST_SetSRID(ST_GeomFromGeoJSON($13),4326) END) AS id
  `, [
    record.namespace, record.sourceId, record.layerId, record.externalId, record.version, record.sha256,
    record.registryScope, record.titleReference, record.plotReference, record.localityKey, record.classification,
    record.allowBoundaryRedistribution, serialized,
  ]);
  if (!rows[0]?.id) throw new Error('ATLAS_RECORD_NOT_SAVED');
  return rows[0].id;
}

type Contribution = {
  readonly event_id: string;
  readonly listing_id: string;
  readonly boundary_id: string;
  readonly method: 'OWNER_TRACED_ESTIMATE' | 'MALAK_DOC_GEOREFERENCED';
  readonly geometry: Polygon;
};

/** Read a bounded full snapshot. Replace only the contributions layer in the consumer. */
export async function readAtlasContributionLayer(database: Pick<SqlPool, 'query'>) {
  const {rows} = await database.query<Contribution>(`
    SELECT event_id,listing_id,boundary_id,method,geometry
    FROM sell.atlas_contribution_features
    ORDER BY event_id LIMIT 1001
  `, []);
  if (rows.length > 1000) throw new Error('ATLAS_CONTRIBUTION_SNAPSHOT_TOO_LARGE');
  return {
    type: 'FeatureCollection' as const,
    layerId: 'MALAK_OWNER_SUBMITTED' as const,
    features: rows.map(row => ({
      type: 'Feature' as const,
      id: row.event_id,
      geometry: row.geometry,
      properties: {listingId: row.listing_id, boundaryId: row.boundary_id, method: row.method},
    })),
  };
}
