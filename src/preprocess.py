#!/usr/bin/env python3
"""
Preprocessing script for the Video Game Ontology project.

Reads raw RAWG CSV files from Dataset/raw_data/, parses the Python-style
JSON columns using ast.literal_eval, and writes flat join tables to Dataset/processed/.

Run from anywhere:
    python src/preprocess.py
"""

import os
import csv
import ast
import math

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
RAW_DIR    = os.path.join(SCRIPT_DIR, '..', 'Dataset', 'raw_data')
OUT_DIR    = os.path.join(SCRIPT_DIR, '..', 'Dataset', 'processed')


def safe_parse(val):
    """Parse a Python-style dict/list string. Returns None on failure."""
    if val is None:
        return None
    if isinstance(val, float) and math.isnan(val):
        return None
    s = str(val).strip()
    if not s or s in ('', 'nan'):
        return None
    try:
        return ast.literal_eval(s)
    except Exception:
        return None


def write_csv(filename, fieldnames, rows):
    path = os.path.join(OUT_DIR, filename)
    with open(path, 'w', newline='', encoding='utf-8') as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, extrasaction='ignore')
        writer.writeheader()
        writer.writerows(rows)
    print(f"  {filename}: {len(rows)} rows")


def read_csv(filename):
    path = os.path.join(RAW_DIR, filename)
    with open(path, newline='', encoding='utf-8') as f:
        return list(csv.DictReader(f))


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    print("=== Video Game Ontology — Preprocessing ===")

    # ------------------------------------------------------------------ #
    # 1.  FLAT ENTITY TABLES  (drop JSON/image columns, keep scalars)
    # ------------------------------------------------------------------ #
    print("\n[1] Flat entity tables")

    genres = read_csv('genres.csv')
    write_csv('genres_flat.csv', ['id', 'name', 'slug', 'games_count'],
              [{'id': r['id'], 'name': r['name'], 'slug': r['slug'],
                'games_count': r['games_count']} for r in genres])

    def to_int_str(val):
        """Convert '2020.0' -> '2020', empty/nan -> ''."""
        s = str(val).strip() if val is not None else ''
        if not s or s == 'nan':
            return ''
        try:
            return str(int(float(s)))
        except (ValueError, TypeError):
            return ''

    platforms = read_csv('platforms.csv')
    write_csv('platforms_flat.csv',
              ['id', 'name', 'slug', 'games_count', 'year_start', 'year_end'],
              [{'id': r['id'], 'name': r['name'], 'slug': r['slug'],
                'games_count': r['games_count'],
                'year_start': to_int_str(r.get('year_start')),
                'year_end':   to_int_str(r.get('year_end'))} for r in platforms])

    parent_platforms = read_csv('parent_platforms.csv')
    write_csv('parent_platforms_flat.csv', ['id', 'name', 'slug'],
              [{'id': r['id'], 'name': r['name'], 'slug': r['slug']}
               for r in parent_platforms])

    stores = read_csv('stores.csv')
    write_csv('stores_flat.csv', ['id', 'name', 'slug', 'domain', 'games_count'],
              [{'id': r['id'], 'name': r['name'], 'slug': r['slug'],
                'domain': r.get('domain', ''),
                'games_count': r['games_count']} for r in stores])

    tags = read_csv('tags.csv')
    write_csv('tags_flat.csv', ['id', 'name', 'slug', 'games_count', 'language'],
              [{'id': r['id'], 'name': r['name'], 'slug': r['slug'],
                'games_count': r['games_count'],
                'language': r.get('language', '')} for r in tags])

    # creator-roles.csv is already flat
    roles = read_csv('creator-roles.csv')
    write_csv('creator_roles_flat.csv', ['id', 'name', 'slug'],
              [{'id': r['id'], 'name': r['name'], 'slug': r['slug']} for r in roles])

    developers = read_csv('developers.csv')
    write_csv('developers_flat.csv', ['id', 'name', 'slug', 'games_count'],
              [{'id': r['id'], 'name': r['name'], 'slug': r['slug'],
                'games_count': r['games_count']} for r in developers])

    publishers = read_csv('publishers.csv')
    write_csv('publishers_flat.csv', ['id', 'name', 'slug', 'games_count'],
              [{'id': r['id'], 'name': r['name'], 'slug': r['slug'],
                'games_count': r['games_count']} for r in publishers])

    creators = read_csv('creators.csv')
    write_csv('creators_flat.csv', ['id', 'name', 'slug'],
              [{'id': r['id'], 'name': r['name'], 'slug': r['slug']}
               for r in creators])

    # ------------------------------------------------------------------ #
    # 2.  GAMES FLAT TABLE + GAMES JOIN TABLES
    # ------------------------------------------------------------------ #
    print("\n[2] Games flat table and join tables")

    games = read_csv('games.csv')

    games_flat            = []
    game_ratings          = []
    game_genres           = []
    game_platforms        = []
    game_parent_platforms = []
    game_stores           = []
    game_tags             = []
    game_esrb             = []

    for row in games:
        gid = row['id']

        # -- metacritic: stored as float string "92.0" -> emit as integer
        mc = row.get('metacritic', '').strip()
        try:
            metacritic = str(int(float(mc))) if mc else ''
        except (ValueError, TypeError):
            metacritic = ''

        # -- released: omit for TBA games
        released = row.get('released', '').strip()
        if row.get('tba', '').strip().lower() == 'true':
            released = ''

        games_flat.append({
            'id':            gid,
            'slug':          row['slug'],
            'name':          row['name'],
            'released':      released,
            'metacritic':    metacritic,
            'playtime':      row.get('playtime', ''),
            'ratings_count': row.get('ratings_count', ''),
        })

        # -- AggregateRating
        rating = row.get('rating', '').strip()
        rating_top = row.get('rating_top', '').strip()
        if rating:
            game_ratings.append({
                'game_id':      gid,
                'rating_value': rating,
                'rating_top':   rating_top,
            })

        # -- genres
        for g in (safe_parse(row.get('genres')) or []):
            game_genres.append({'game_id': gid, 'genre_id': g['id']})

        # -- specific platforms
        for entry in (safe_parse(row.get('platforms')) or []):
            plat = entry.get('platform', {})
            if plat.get('id') is not None:
                game_platforms.append({'game_id': gid, 'platform_id': plat['id']})

        # -- parent platforms
        for entry in (safe_parse(row.get('parent_platforms')) or []):
            plat = entry.get('platform', {})
            if plat.get('id') is not None:
                game_parent_platforms.append({
                    'game_id':            gid,
                    'parent_platform_id': plat['id'],
                })

        # -- stores
        for entry in (safe_parse(row.get('stores')) or []):
            store = entry.get('store', {})
            if store.get('id') is not None:
                game_stores.append({'game_id': gid, 'store_id': store['id']})

        # -- tags
        for t in (safe_parse(row.get('tags')) or []):
            game_tags.append({'game_id': gid, 'tag_id': t['id']})

        # -- ESRB rating
        esrb = safe_parse(row.get('esrb_rating'))
        if esrb and esrb.get('id') is not None:
            game_esrb.append({
                'game_id':   gid,
                'esrb_id':   esrb['id'],
                'esrb_name': esrb.get('name', ''),
                'esrb_slug': esrb.get('slug', ''),
            })

    write_csv('games_flat.csv',
              ['id', 'slug', 'name', 'released', 'metacritic', 'playtime', 'ratings_count'],
              games_flat)
    write_csv('game_ratings.csv',
              ['game_id', 'rating_value', 'rating_top'],
              game_ratings)
    write_csv('game_genres.csv',             ['game_id', 'genre_id'],            game_genres)
    write_csv('game_platforms.csv',          ['game_id', 'platform_id'],         game_platforms)
    write_csv('game_parent_platforms.csv',   ['game_id', 'parent_platform_id'],  game_parent_platforms)
    write_csv('game_stores.csv',             ['game_id', 'store_id'],            game_stores)
    write_csv('game_tags.csv',               ['game_id', 'tag_id'],              game_tags)
    write_csv('game_esrb.csv',
              ['game_id', 'esrb_id', 'esrb_name', 'esrb_slug'],                 game_esrb)

    # ------------------------------------------------------------------ #
    # 3.  DEVELOPER / PUBLISHER / CREATOR JOIN TABLES
    # ------------------------------------------------------------------ #
    print("\n[3] Developer, publisher, creator join tables")

    dev_games = []
    for row in developers:
        for g in (safe_parse(row.get('games')) or []):
            dev_games.append({'developer_id': row['id'], 'game_id': g['id']})
    write_csv('developer_games.csv', ['developer_id', 'game_id'], dev_games)

    pub_games = []
    for row in publishers:
        for g in (safe_parse(row.get('games')) or []):
            pub_games.append({'publisher_id': row['id'], 'game_id': g['id']})
    write_csv('publisher_games.csv', ['publisher_id', 'game_id'], pub_games)

    creator_games     = []
    creator_positions = []
    for row in creators:
        for g in (safe_parse(row.get('games')) or []):
            creator_games.append({'creator_id': row['id'], 'game_id': g['id']})
        for pos in (safe_parse(row.get('positions')) or []):
            creator_positions.append({'creator_id': row['id'], 'role_id': pos['id']})
    write_csv('creator_games.csv',     ['creator_id', 'game_id'], creator_games)
    write_csv('creator_positions.csv', ['creator_id', 'role_id'], creator_positions)

    # ------------------------------------------------------------------ #
    # 4.  PLATFORM -> PARENT PLATFORM MAPPING
    # ------------------------------------------------------------------ #
    print("\n[4] Platform -> ParentPlatform mapping")

    platform_parent = []
    for row in parent_platforms:
        pp_id = row['id']
        for plat in (safe_parse(row.get('platforms')) or []):
            if plat.get('id') is not None:
                platform_parent.append({
                    'platform_id':        plat['id'],
                    'parent_platform_id': pp_id,
                })
    write_csv('platform_parent_platform.csv',
              ['platform_id', 'parent_platform_id'],
              platform_parent)

    print(f"\n=== Preprocessing complete — output in: {OUT_DIR} ===")


if __name__ == '__main__':
    main()
