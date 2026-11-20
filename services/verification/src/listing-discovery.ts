import type {IncomingMessage, ServerResponse} from 'node:http';
import type {SqlPool} from './postgres-evidence-repository.ts';

const listPageSize = 40;
const photoPath = /^[A-Za-z0-9][A-Za-z0-9/_-]{0,246}\.(?:jpg|jpeg|png|webp)$/;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const placeIdentifier = /^[a-z0-9][a-z0-9._:-]{0,95}$/;
const decimal = /^(?:0|[1-9]\d{0,14})(?:\.\d{1,3})?$/;
const timestamp = /^(?!0000)\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$/;
const discoveryParams = [
  'bbox', 'placeId', 'kind', 'minPrice', 'maxPrice', 'minArea',
  'maxArea', 'minRooms', 'lang', 'sort',
];

type Kind = 'LAND' | 'APARTMENT' | 'HOUSE';
type Language = 'ar' | 'fr';
type Bounds = readonly [number, number, number, number];
type Cursor = {readonly publishedAt: string; readonly id: string};

interface SearchFilters {
  readonly bbox: Bounds;
  readonly placeId: string | null;
  readonly kind: Kind | null;
  readonly minPrice: string | null;
  readonly maxPrice: string | null;
  readonly minArea: string | null;
  readonly maxArea: string | null;
  readonly minRooms: number | null;
}

interface ListingRow {
  readonly id: string;
  readonly title: string;
  readonly region_ar: string | null;
  readonly region_fr: string | null;
  readonly kind: Kind;
  readonly price_tnd: string;
  readonly area_m2: string | null;
  readonly rooms: number | null;
  readonly latitude: number | null;
  readonly longitude: number | null;
  readonly location_accuracy: 'APPROXIMATE' | 'AREA_ONLY' | 'UNKNOWN';
  readonly photo_asset_path: string | null;
  readonly description: string | null;
  readonly cursor_time: string;
}

interface ClusterRow {
  readonly bucket: string;
  readonly point_count: number;
  readonly center_latitude: number;
  readonly center_longitude: number;
  readonly listing: ListingRow | null;
}

interface Place {
  readonly id: string;
  readonly label: string;
  readonly latitude: number;
  readonly longitude: number;
}

const LISTING_COLUMNS = `
  d.id::text AS id,d.title,d.region_ar,d.region_fr,d.kind,
  d.price_tnd::text AS price_tnd,d.area_m2::text AS area_m2,d.rooms,
  ST_Y(d.public_point) AS latitude,ST_X(d.public_point) AS longitude,
  d.location_accuracy,d.photo_asset_path,d.description,
  to_char(d.published_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS cursor_time`;

const FILTERED = `
  FROM sell.listing_discovery d
  WHERE ($5::text IS NULL OR d.place_id=$5)
    AND ($6::text IS NULL OR d.kind=$6)
    AND ($7::numeric IS NULL OR d.price_tnd>=$7)
    AND ($8::numeric IS NULL OR d.price_tnd<=$8)
    AND ($9::numeric IS NULL OR d.area_m2>=$9)
    AND ($10::numeric IS NULL OR d.area_m2<=$10)
    AND ($11::smallint IS NULL OR d.rooms>=$11)
    AND (
      ST_Intersects(d.public_point, ST_MakeEnvelope($1,$2,$3,$4,4326))
      OR (d.public_point IS NULL
        AND ST_Intersects(d.place_center, ST_MakeEnvelope($1,$2,$3,$4,4326)))
    )`;

const LIST = `SELECT ${LISTING_COLUMNS} ${FILTERED}
  AND ($12::timestamptz IS NULL OR (d.published_at,d.id)<($12,$13::uuid))
  ORDER BY d.published_at DESC,d.id DESC LIMIT $14::integer`;

const CLUSTERS = `
  WITH visible AS MATERIALIZED (
    SELECT d.* ${FILTERED} AND d.public_point IS NOT NULL
  ), mode AS (
    SELECT $12::integer<12 OR count(*)>200 AS group_points FROM visible
  ), cells AS (
    SELECT v.id,v.public_point,
      CASE WHEN mode.group_points
        THEN least(7,greatest(0,floor((ST_X(v.public_point)-$1)*8/($3-$1)))::integer)
        ELSE 0 END AS x,
      CASE WHEN mode.group_points
        THEN least(7,greatest(0,floor((ST_Y(v.public_point)-$2)*8/($4-$2)))::integer)
        ELSE 0 END AS y,
      CASE WHEN NOT mode.group_points THEN v.id END AS separate_id
    FROM visible v CROSS JOIN mode
  ), buckets AS (
    SELECT x,y,separate_id,count(*)::integer AS point_count, min(id::text)::uuid AS representative_id,
           avg(ST_Y(public_point)) AS center_latitude,avg(ST_X(public_point)) AS center_longitude
    FROM cells GROUP BY x,y,separate_id
  )
  SELECT concat(b.x,':',b.y) AS bucket,b.point_count,b.center_latitude,b.center_longitude,
    CASE WHEN b.point_count=1 THEN (
      SELECT to_jsonb(card) FROM (SELECT ${LISTING_COLUMNS} FROM visible d WHERE d.id=b.representative_id) card
    ) END AS listing
  FROM buckets b
  ORDER BY b.x,b.y,b.separate_id`;

const PLACES = `
  WITH needle AS (SELECT sell.fold_discovery_place($1::text) AS prefix)
  SELECT p.id,CASE WHEN $2::text='ar' THEN p.name_ar ELSE p.name_fr END AS label,
    ST_Y(p.center) AS latitude,ST_X(p.center) AS longitude
  FROM sell.discovery_places p CROSS JOIN needle
  WHERE needle.prefix<>''
    AND (starts_with(p.search_ar,needle.prefix) OR starts_with(p.search_fr,needle.prefix)
    OR EXISTS (
      SELECT 1 FROM sell.discovery_place_aliases a
      WHERE a.place_id=p.id AND starts_with(a.search_alias,needle.prefix)
    ))
  ORDER BY CASE WHEN p.search_ar=needle.prefix OR p.search_fr=needle.prefix THEN 0 ELSE 1 END,
    CASE WHEN $2='ar' THEN p.search_ar ELSE p.search_fr END,p.id
  LIMIT 10`;

function filterValues(filters: SearchFilters) {
  return [
    ...filters.bbox, filters.placeId, filters.kind, filters.minPrice, filters.maxPrice,
    filters.minArea, filters.maxArea, filters.minRooms,
  ];
}

function approvedMediaOrigin(value?: string): string | null {
  if (value === undefined) return null;
  const url = new URL(value);
  if (url.protocol !== 'https:' || url.username || url.password ||
      url.pathname !== '/' || url.search || url.hash) {
    throw new TypeError('DISCOVERY_HTTPS_MEDIA_ORIGIN_REQUIRED');
  }
  return url.origin;
}

export class PostgresListingDiscoveryRepository {
  private readonly pool: Pick<SqlPool, 'query'>;
  private readonly mediaOrigin: string | null;

  constructor(pool: Pick<SqlPool, 'query'>, mediaOrigin?: string) {
    this.pool = pool;
    this.mediaOrigin = approvedMediaOrigin(mediaOrigin);
  }

  private card(row: ListingRow, lang: Language) {
    const asset = row.photo_asset_path;
    const photoUrl = this.mediaOrigin && asset && photoPath.test(asset) && !asset.includes('//')
      ? this.mediaOrigin + '/' + asset : null;
    return {
      id: row.id,
      title: row.title,
      region: (lang === 'ar' ? row.region_ar : row.region_fr) ?? '',
      kind: row.kind,
      priceTnd: row.price_tnd,
      areaM2: row.area_m2,
      rooms: row.rooms,
      latitude: row.latitude,
      longitude: row.longitude,
      locationAccuracy: row.location_accuracy,
      photoUrl,
      description: row.description,
    };
  }

  async suggest(query: string, lang: Language) {
    return (await this.pool.query<Place>(PLACES, [query, lang])).rows;
  }

  async list(filters: SearchFilters, lang: Language, cursor: Cursor | null) {
    const values = [...filterValues(filters), cursor?.publishedAt ?? null, cursor?.id ?? null, listPageSize + 1];
    const {rows} = await this.pool.query<ListingRow>(LIST, values);
    const page = rows.slice(0, listPageSize);
    const last = page.at(-1);
    return {
      listings: page.map(row => this.card(row, lang)),
      nextCursor: rows.length > listPageSize && last
        ? encodeCursor({publishedAt: last.cursor_time, id: last.id}) : null,
    };
  }

  async clusters(filters: SearchFilters, lang: Language, zoom: number) {
    const {rows} = await this.pool.query<ClusterRow>(CLUSTERS, [...filterValues(filters), zoom]);
    return {
      clusters: rows.filter(row => row.point_count > 1).map(row => ({
        id: row.bucket,
        latitude: row.center_latitude,
        longitude: row.center_longitude,
        count: row.point_count,
      })),
      listings: rows.flatMap(row => row.listing ? [this.card(row.listing, lang)] : []),
    };
  }

  async detail(id: string, lang: Language) {
    const {rows} = await this.pool.query<ListingRow>(
      `SELECT ${LISTING_COLUMNS} FROM sell.listing_discovery d WHERE d.id=$1::uuid`, [id],
    );
    return rows[0] ? this.card(rows[0], lang) : null;
  }
}

class InvalidQuery extends Error {}

function checkParameters(params: URLSearchParams, keys: readonly string[]) {
  for (const key of params.keys()) {
    if (!keys.includes(key) || params.getAll(key).length !== 1) throw new InvalidQuery();
  }
}

function readLanguage(params: URLSearchParams): Language {
  const lang = params.get('lang') ?? 'fr';
  if (lang !== 'ar' && lang !== 'fr') throw new InvalidQuery();
  return lang;
}

function readBounds(raw: string | null): Bounds {
  const parts = raw?.split(',');
  if (parts?.length !== 4 || parts.some(part => !/^-?\d{1,3}(?:\.\d{1,16})?$/.test(part))) {
    throw new InvalidQuery();
  }
  const [west, south, east, north] = parts.map(Number);
  if (west < -180 || east > 180 || south < -85.051129 || north > 85.051129 ||
      east <= west || north <= south || east - west > 16 || north - south > 16) {
    throw new InvalidQuery();
  }
  return [west, south, east, north];
}

function readAmount(params: URLSearchParams, name: string) {
  const amount = params.get(name);
  if (amount !== null && !decimal.test(amount)) throw new InvalidQuery();
  return amount;
}

function millisOf(amount: string) {
  const [whole, fraction = ''] = amount.split('.');
  return BigInt(whole + fraction.padEnd(3, '0'));
}

function readSmallInteger(raw: string, ceiling: number) {
  if (!/^(?:0|[1-9]\d{0,2})$/.test(raw) || Number(raw) > ceiling) throw new InvalidQuery();
  return Number(raw);
}

function readFilters(params: URLSearchParams): SearchFilters {
  const bbox = readBounds(params.get('bbox'));
  const placeId = params.get('placeId');
  const kind = params.get('kind');
  const sort = params.get('sort');
  if (placeId !== null && !placeIdentifier.test(placeId)) throw new InvalidQuery();
  if (kind !== null && kind !== 'LAND' && kind !== 'APARTMENT' && kind !== 'HOUSE') throw new InvalidQuery();
  if (sort !== null && sort !== 'newest') throw new InvalidQuery();

  const minPrice = readAmount(params, 'minPrice');
  const maxPrice = readAmount(params, 'maxPrice');
  const minArea = readAmount(params, 'minArea');
  const maxArea = readAmount(params, 'maxArea');
  if ((minPrice !== null && maxPrice !== null && millisOf(minPrice) > millisOf(maxPrice)) ||
      (minArea !== null && maxArea !== null && millisOf(minArea) > millisOf(maxArea))) throw new InvalidQuery();

  const rooms = params.get('minRooms');
  const minRooms = rooms === null ? null : readSmallInteger(rooms, 100);
  return {bbox, placeId, kind, minPrice, maxPrice, minArea, maxArea, minRooms};
}

function encodeCursor(cursor: Cursor) {
  return Buffer.from(cursor.publishedAt + ',' + cursor.id).toString('base64url');
}

function readCursor(raw: string | null): Cursor | null {
  if (raw === null) return null;
  if (!/^[A-Za-z0-9_-]{86}$/.test(raw)) throw new InvalidQuery();
  const [publishedAt, id] = Buffer.from(raw, 'base64url').toString('utf8').split(',');
  if (!publishedAt || !id || !timestamp.test(publishedAt) || !uuid.test(id) ||
      encodeCursor({publishedAt, id}) !== raw) throw new InvalidQuery();
  const date = new Date(publishedAt);
  if (Number.isNaN(date.getTime()) || date.toISOString().slice(0, 19) !== publishedAt.slice(0, 19)) {
    throw new InvalidQuery();
  }
  return {publishedAt, id};
}

type HandlerOptions = {readonly enabled: false} | {
  readonly enabled: true;
  readonly repository: PostgresListingDiscoveryRepository;
  readonly allowRequest: (remoteAddress: string) => Promise<boolean>;
};

function respond(res: ServerResponse, status: number, data: object) {
  res.setHeader('cache-control', 'no-store');
  res.setHeader('content-type', 'application/json; charset=utf-8');
  res.setHeader('x-content-type-options', 'nosniff');
  res.writeHead(status).end(JSON.stringify(data));
}

/** Handles only public discovery GETs, leaving every other path to the existing router. */
export function createListingDiscoveryHttpHandler(options: HandlerOptions) {
  return async (req: IncomingMessage, res: ServerResponse): Promise<boolean> => {
    if (!options.enabled || !req.url?.startsWith('/') || req.url.startsWith('//')) return false;
    const path = req.url.split('?', 1)[0];
    const detail = /^\/api\/v1\/listings\/([^/]+)$/.exec(path);
    const suggest = path === '/api/v1/places/suggest';
    const clusters = path === '/api/v1/discovery/clusters';
    const list = path === '/api/v1/discovery' || path === '/api/v1/discovery/list';
    if (!detail && !suggest && !clusters && !list) return false;

    try {
      if (req.method !== 'GET') {
        res.setHeader('allow', 'GET');
        respond(res, 405, {code: 'METHOD_NOT_ALLOWED'});
      } else if (await options.allowRequest(req.socket.remoteAddress ?? 'UNKNOWN') !== true) {
        respond(res, 429, {code: 'RATE_LIMITED'});
      } else if (req.url.length > 4096) {
        throw new InvalidQuery();
      } else {
        const params = new URL(req.url, 'http://localhost').searchParams;
        if (detail) {
          checkParameters(params, ['lang']);
          const lang = readLanguage(params);
          const listing = uuid.test(detail[1]) ? await options.repository.detail(detail[1], lang) : null;
          if (listing) respond(res, 200, {listing});
          else respond(res, 404, {code: 'LISTING_NOT_FOUND'});
        } else if (suggest) {
          checkParameters(params, ['q', 'lang']);
          const query = params.get('q')?.trim();
          if (!query || query.length > 100 || /\p{Cc}/u.test(query)) throw new InvalidQuery();
          const places = await options.repository.suggest(query, readLanguage(params));
          respond(res, 200, {places});
        } else if (clusters) {
          checkParameters(params, [...discoveryParams, 'zoom']);
          const zoom = readSmallInteger(params.get('zoom') ?? '', 22);
          respond(res, 200, await options.repository.clusters(readFilters(params), readLanguage(params), zoom));
        } else {
          checkParameters(params, [...discoveryParams, 'cursor']);
          const cursor = readCursor(params.get('cursor'));
          respond(res, 200, await options.repository.list(readFilters(params), readLanguage(params), cursor));
        }
      }
    } catch (error) {
      if (error instanceof InvalidQuery) respond(res, 400, {code: 'INVALID_DISCOVERY_QUERY'});
      else respond(res, 503, {code: 'DISCOVERY_UNAVAILABLE'});
    }
    return true;
  };
}
