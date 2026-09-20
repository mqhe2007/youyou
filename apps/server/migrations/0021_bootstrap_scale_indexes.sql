-- Bootstrap reads choose one verified location per asset and then emit media in
-- owner/sort order.  Keep both parts index-backed so a large user library does
-- not repeatedly scan and sort the entire locations table.
CREATE INDEX IF NOT EXISTS media_locations_verified_asset_order_idx
    ON media_locations (media_asset_id, storage_id, normalized_path, id)
    WHERE hash_state = 'verified';

CREATE INDEX IF NOT EXISTS media_assets_owner_state_sort_idx
    ON media_assets (owner_user_id, identity_state, sort_at DESC, id DESC);
