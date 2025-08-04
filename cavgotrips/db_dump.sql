--
-- PostgreSQL database dump
--

-- Dumped from database version 15.13
-- Dumped by pg_dump version 15.13

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: public; Type: SCHEMA; Schema: -; Owner: postgres
--

-- *not* creating schema, since initdb creates it


ALTER SCHEMA public OWNER TO postgres;

--
-- Name: SCHEMA public; Type: COMMENT; Schema: -; Owner: postgres
--

COMMENT ON SCHEMA public IS '';


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: locations; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.locations (
    id bigint NOT NULL,
    latitude numeric NOT NULL,
    code text,
    longitude numeric NOT NULL,
    google_place_name text,
    custom_name text,
    province text,
    district text,
    place_id text,
    created_at timestamp with time zone,
    updated_at timestamp with time zone
);


ALTER TABLE public.locations OWNER TO postgres;

--
-- Name: locations_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.locations_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.locations_id_seq OWNER TO postgres;

--
-- Name: locations_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.locations_id_seq OWNED BY public.locations.id;


--
-- Name: route_waypoints; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.route_waypoints (
    id bigint NOT NULL,
    route_id bigint,
    location_id bigint,
    "order" bigint NOT NULL,
    price numeric NOT NULL,
    created_at timestamp with time zone
);


ALTER TABLE public.route_waypoints OWNER TO postgres;

--
-- Name: route_waypoints_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.route_waypoints_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.route_waypoints_id_seq OWNER TO postgres;

--
-- Name: route_waypoints_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.route_waypoints_id_seq OWNED BY public.route_waypoints.id;


--
-- Name: routes; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.routes (
    id bigint NOT NULL,
    name text,
    distance_meters bigint,
    estimated_duration_seconds bigint,
    google_route_id text,
    origin_id bigint,
    destination_id bigint,
    route_price numeric,
    city_route boolean DEFAULT false,
    created_at timestamp with time zone,
    updated_at timestamp with time zone
);


ALTER TABLE public.routes OWNER TO postgres;

--
-- Name: routes_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.routes_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.routes_id_seq OWNER TO postgres;

--
-- Name: routes_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.routes_id_seq OWNED BY public.routes.id;


--
-- Name: sse_sessions; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.sse_sessions (
    id bigint NOT NULL,
    uuid text NOT NULL,
    trip_ids jsonb,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    expires_at timestamp with time zone
);


ALTER TABLE public.sse_sessions OWNER TO postgres;

--
-- Name: sse_sessions_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.sse_sessions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.sse_sessions_id_seq OWNER TO postgres;

--
-- Name: sse_sessions_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.sse_sessions_id_seq OWNED BY public.sse_sessions.id;


--
-- Name: trip_waypoints; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.trip_waypoints (
    id bigint NOT NULL,
    trip_id bigint,
    location_id bigint,
    "order" bigint NOT NULL,
    price numeric,
    is_passed boolean DEFAULT false,
    is_next boolean DEFAULT false,
    passed_timestamp bigint,
    remaining_time bigint,
    remaining_distance numeric,
    is_custom boolean DEFAULT false,
    created_at timestamp with time zone,
    updated_at timestamp with time zone
);


ALTER TABLE public.trip_waypoints OWNER TO postgres;

--
-- Name: trip_waypoints_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.trip_waypoints_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.trip_waypoints_id_seq OWNER TO postgres;

--
-- Name: trip_waypoints_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.trip_waypoints_id_seq OWNED BY public.trip_waypoints.id;


--
-- Name: trips; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.trips (
    id bigint NOT NULL,
    route_id bigint,
    vehicle_id bigint NOT NULL,
    vehicle jsonb,
    status text NOT NULL,
    departure_time bigint NOT NULL,
    completion_time bigint,
    connection_mode text NOT NULL,
    notes text,
    seats bigint NOT NULL,
    remaining_time_to_destination bigint,
    remaining_distance_to_destination numeric,
    is_reversed boolean DEFAULT false,
    current_speed numeric,
    current_latitude numeric,
    current_longitude numeric,
    has_custom_waypoints boolean DEFAULT false,
    created_at timestamp with time zone,
    updated_at timestamp with time zone
);


ALTER TABLE public.trips OWNER TO postgres;

--
-- Name: trips_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.trips_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.trips_id_seq OWNER TO postgres;

--
-- Name: trips_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.trips_id_seq OWNED BY public.trips.id;


--
-- Name: locations id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.locations ALTER COLUMN id SET DEFAULT nextval('public.locations_id_seq'::regclass);


--
-- Name: route_waypoints id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.route_waypoints ALTER COLUMN id SET DEFAULT nextval('public.route_waypoints_id_seq'::regclass);


--
-- Name: routes id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.routes ALTER COLUMN id SET DEFAULT nextval('public.routes_id_seq'::regclass);


--
-- Name: sse_sessions id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.sse_sessions ALTER COLUMN id SET DEFAULT nextval('public.sse_sessions_id_seq'::regclass);


--
-- Name: trip_waypoints id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.trip_waypoints ALTER COLUMN id SET DEFAULT nextval('public.trip_waypoints_id_seq'::regclass);


--
-- Name: trips id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.trips ALTER COLUMN id SET DEFAULT nextval('public.trips_id_seq'::regclass);


--
-- Data for Name: locations; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.locations (id, latitude, code, longitude, google_place_name, custom_name, province, district, place_id, created_at, updated_at) FROM stdin;
11	-1.5749923389416813	25002	30.067963579107044	Gicumbi, Amajyaruguru, Rwanda	Gicumbi (Gare)	North	Gicumbi	here:cm:namedplace:25727264	2025-08-02 21:44:32.872549+00	2025-08-02 21:44:32.872549+00
26	-1.613883738283373	25004	29.980828838375714	Miyove, Amajyaruguru, Rwanda	Miyove	North	Gicumbi	here:cm:namedplace:25727576	2025-08-03 07:59:43.384918+00	2025-08-03 07:59:43.384918+00
25	-1.62933297570853	25010	30.103166431159654	Musanze, Amajyaruguru, Rwanda	Rukomo	North	Gicumbi	here:cm:namedplace:25727028	0001-01-01 00:00:00+00	2025-08-04 09:01:02.712273+00
39	-1.61123922549717	22001	29.768362915066742	Gatsata, Umujyi Wa Kigali, Rwanda	Gakenke (Gare)	North	Gakenke	here:cm:namedplace:25726891	2025-08-04 09:24:23.549137+00	2025-08-04 09:24:23.549137+00
40	-1.4942748537001693	21003	29.930035958610677	Kivugiza, Byumba, Amajyaruguru, Rwanda	Kivuye (Isoko)	North	Burera	here:cm:namedplace:25726335	2025-08-04 13:01:55.897768+00	2025-08-04 13:01:55.897768+00
29	-1.57496811583543	25005	30.067878159374025	Gishore, Nyakaliro, Iburasirazuba, Rwanda	Gicumbi Gare	North	Gicumbi	here:cm:namedplace:25724867	2025-08-03 20:52:09.615733+00	2025-08-03 20:52:09.615733+00
30	-1.7563825702135571	25006	30.124854378087818	Tetero, Kigali, Umujyi Wa Kigali, Rwanda	Tetero	North	Gicumbi	here:cm:namedplace:25727665	2025-08-03 20:52:09.996343+00	2025-08-03 20:52:09.996343+00
31	-1.4262340873278725	25007	30.013077509420512	Byumba, Amajyaruguru, Rwanda	Gatuna	North	Gicumbi	here:cm:namedplace:25726869	2025-08-03 21:35:59.614481+00	2025-08-03 21:35:59.614481+00
32	-1.5750903927959943	25008	30.06791042414297	Gitatsa, Kisaro, Amajyaruguru, Rwanda	\N	North	Gicumbi	here:cm:namedplace:25727062	2025-08-04 05:40:10.584545+00	2025-08-04 05:40:10.584545+00
41	-1.4429207791867558	23002	29.585069049208826	Kinigi, Amajyaruguru, Rwanda	Kinigi	North	Musanze	here:cm:namedplace:25727025	2025-08-04 13:30:24.049559+00	2025-08-04 13:30:24.049559+00
33	-1.4824804872877473	21001	29.91873165355147	Kigali Heights	Kuvuye Ku Isoko	North	Burera	here:pds:place:646kxtku-b040d35713564e34b4ea6b2255a4e0cc	2025-08-04 06:58:38.48702+00	2025-08-04 06:58:38.48702+00
34	-1.417311542812699	21002	29.833381987855073	Buyoga, Amajyaruguru, Rwanda	Butaro Ku Rusumo	North	Burera	here:cm:namedplace:25726850	2025-08-04 06:58:38.878086+00	2025-08-04 06:58:38.878086+00
35	-1.6467493246479523	25009	30.121139155023865	Rubaya, Kabale, Uganda	Rutare	North	Gicumbi	here:cm:namedplace:27830979	2025-08-04 07:15:53.191978+00	2025-08-04 07:15:53.191978+00
37	-1.5110126966732758	23001	29.641751467787437	Rukomo, Iburasirazuba, Rwanda	Musanze (Gare)	North	Musanze	here:cm:namedplace:25727547	2025-08-04 08:05:48.892689+00	2025-08-04 08:05:48.892689+00
10	-1.6139398992283163	25001	29.98093098517106	Rukomo, Amajyaruguru, Rwanda	Miyove Center	North	Gicumbi	here:cm:namedplace:25726792	0001-01-01 00:00:00+00	2025-08-04 08:09:39.407706+00
38	-1.941068983688995	13001	30.045276366795928	Nyabisindu, Kigali, Umujyi Wa Kigali, Rwanda	Nyabugogo (Gare)	Kigali	Nyarugenge	here:cm:namedplace:25727542	2025-08-04 08:16:34.701051+00	2025-08-04 08:16:34.701051+00
15	-1.7623037156933032	25003	30.12368232528068	Gaseke, Mutete, Amajyaruguru, Rwanda	Gaseke	North	Gicumbi	here:cm:namedplace:25726615	0001-01-01 00:00:00+00	2025-08-04 08:16:35.295622+00
42	-1.4432	21004	29.70626	Gahunga, Amajyaruguru, Rwanda	Gahunga	North	Burera	here:cm:namedplace:25726971	2025-08-04 13:35:26.078598+00	2025-08-04 13:35:26.078598+00
43	-1.6144162711250027	55001	29.50169403707237	Nyirakigugu, Jenda, Iburengerazuba, Rwanda	Mukamira	West	Rubavu	here:cm:namedplace:25725729	2025-08-04 13:47:17.062684+00	2025-08-04 13:47:17.062684+00
44	-1.38293	21005	29.74171	Cyanika, Amajyaruguru, Rwanda	Cyanika (RSSB Burera)	North	Burera	here:cm:namedplace:25726884	2025-08-04 13:57:13.170912+00	2025-08-04 13:57:13.170912+00
45	-1.692638570183747	53001	29.634510860227447	Shyira, Iburengerazuba, Rwanda	Vunga	West	Nyabihu	here:cm:namedplace:25726836	2025-08-04 14:12:30.708847+00	2025-08-04 14:12:30.708847+00
46	-1.6983951707087015	55002	29.34278265290225	Mahoko	Mahoko (Gare)	West	Rubavu	here:pds:place:646kxsy7-ef94559dc72948498571dc313f1e6dfb	2025-08-04 14:37:09.973504+00	2025-08-04 14:37:09.973504+00
27	-1.6573416291378271	24001	29.87874109228466	Base, Amajyaruguru, Rwanda	Base	North	Rulindo	here:cm:namedplace:25726841	0001-01-01 00:00:00+00	2025-08-04 14:39:26.745254+00
47	-1.4952592882001916	21006	29.834620266978177	Kirabo, Busengo, Amajyaruguru, Rwanda	Kirambo	North	Burera	here:cm:namedplace:25725372	2025-08-04 14:44:38.293227+00	2025-08-04 14:44:38.293227+00
48	-1.7435087148017916	55003	29.542605128775293	Kabale, Uganda	Kabaya	West	Rubavu	here:cm:namedplace:27830325	2025-08-04 14:53:22.848848+00	2025-08-04 14:53:22.848848+00
\.


--
-- Data for Name: route_waypoints; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.route_waypoints (id, route_id, location_id, "order", price, created_at) FROM stdin;
\.


--
-- Data for Name: routes; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.routes (id, name, distance_meters, estimated_duration_seconds, google_route_id, origin_id, destination_id, route_price, city_route, created_at, updated_at) FROM stdin;
28	\N	8400	540	\N	25	29	307	f	2025-08-04 11:17:40.704464+00	2025-08-04 11:17:40.704464+00
29	\N	20700	660	\N	15	25	672	f	2025-08-04 11:19:07.227318+00	2025-08-04 11:19:07.227318+00
30	\N	24400	1440	\N	10	27	702	f	2025-08-04 11:20:20.113643+00	2025-08-04 11:20:20.113643+00
31	\N	29000	1020	\N	38	15	863	f	2025-08-04 11:22:16.661316+00	2025-08-04 11:22:16.661316+00
32	\N	28500	1260	\N	11	30	877	f	2025-08-04 11:23:51.230089+00	2025-08-04 11:23:51.230089+00
33	\N	29200	1260	\N	15	11	921	f	2025-08-04 11:25:26.068385+00	2025-08-04 11:25:26.068385+00
34	\N	28400	960	\N	25	31	1038	f	2025-08-04 11:28:42.08679+00	2025-08-04 11:28:42.08679+00
35	\N	36900	1560	\N	11	31	1082	f	2025-08-04 11:31:27.698686+00	2025-08-04 11:31:27.698686+00
36	\N	44300	2220	\N	26	39	1111	f	2025-08-04 11:32:21.452056+00	2025-08-04 11:32:21.452056+00
37	\N	45800	2760	\N	11	27	1462	f	2025-08-04 11:45:54.305338+00	2025-08-04 11:45:54.305338+00
38	\N	11800	780	\N	11	35	1462	f	2025-08-04 11:50:20.156204+00	2025-08-04 11:50:20.156204+00
39	\N	49700	1740	\N	38	25	1506	f	2025-08-04 11:51:15.25107+00	2025-08-04 11:51:15.25107+00
40	\N	49200	1680	\N	15	31	1535	f	2025-08-04 11:52:52.677747+00	2025-08-04 11:52:52.677747+00
41	\N	58200	2340	\N	38	11	1696	f	2025-08-04 11:54:46.581475+00	2025-08-04 11:54:46.581475+00
42	\N	65700	3540	\N	11	39	2003	f	2025-08-04 11:56:52.752948+00	2025-08-04 11:56:52.752948+00
43	\N	29200	1800	\N	11	40	2016	f	2025-08-04 13:01:56.628745+00	2025-08-04 13:01:56.628745+00
44	\N	23700	1380	\N	40	34	1200	f	2025-08-04 13:03:19.495274+00	2025-08-04 13:03:19.495274+00
45	\N	28400	1680	\N	10	25	2047	f	2025-08-04 13:04:25.659569+00	2025-08-04 13:04:25.659569+00
46	\N	78200	2760	\N	38	31	2398	f	2025-08-04 13:05:18.583745+00	2025-08-04 13:05:18.583745+00
47	\N	89200	3600	\N	37	11	3216	f	2025-08-04 13:16:46.148883+00	2025-08-04 13:16:46.148883+00
48	\N	52900	3240	\N	11	34	3216	f	2025-08-04 13:17:49.886445+00	2025-08-04 13:17:49.886445+00
49	\N	45000	2400	\N	37	34	1900	f	2025-08-04 13:25:56.728135+00	2025-08-04 13:25:56.728135+00
50	\N	11600	720	\N	37	41	322	f	2025-08-04 13:30:24.476029+00	2025-08-04 13:30:24.476029+00
51	\N	19900	720	\N	27	39	380	f	2025-08-04 13:31:35.302198+00	2025-08-04 13:31:35.302198+00
52	\N	13500	660	\N	37	42	512	f	2025-08-04 13:35:26.653984+00	2025-08-04 13:35:26.653984+00
53	\N	24300	1080	\N	37	43	775	f	2025-08-04 13:47:17.482597+00	2025-08-04 13:47:17.482597+00
54	\N	21500	960	\N	37	44	775	f	2025-08-04 13:57:13.53406+00	2025-08-04 13:57:13.53406+00
55	\N	23700	1380	\N	37	45	950	f	2025-08-04 14:12:31.073993+00	2025-08-04 14:12:31.073993+00
56	\N	23500	960	\N	39	37	965	f	2025-08-04 14:13:55.508327+00	2025-08-04 14:13:55.508327+00
57	\N	43400	1740	\N	27	37	1330	f	2025-08-04 14:30:44.901161+00	2025-08-04 14:30:44.901161+00
58	\N	49600	2040	\N	37	46	1550	f	2025-08-04 14:37:10.400753+00	2025-08-04 14:37:10.400753+00
59	\N	47000	1800	\N	38	27	1593	f	2025-08-04 14:39:27.152846+00	2025-08-04 14:39:27.152846+00
60	\N	24900	1440	\N	27	47	1623	f	2025-08-04 14:44:38.720152+00	2025-08-04 14:44:38.720152+00
61	\N	49100	2520	\N	37	48	1667	f	2025-08-04 14:53:23.228758+00	2025-08-04 14:53:23.228758+00
62	\N	47300	1920	\N	39	43	1740	f	2025-08-04 14:59:11.646938+00	2025-08-04 14:59:11.646938+00
\.


--
-- Data for Name: sse_sessions; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.sse_sessions (id, uuid, trip_ids, created_at, updated_at, expires_at) FROM stdin;
39	bc6d6f8c44db9e93a3afc670f5985d75	[1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20]	2025-08-04 16:52:40.265854+00	2025-08-04 16:52:40.265854+00	2025-08-04 17:02:40.26563+00
40	d316d750d4142be15d5adda0529e6f96	[1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30]	2025-08-04 16:52:40.266913+00	2025-08-04 16:52:40.266913+00	2025-08-04 17:02:40.266722+00
41	ae8a31d926a463da62961bf0f4e195a3	[1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20]	2025-08-04 16:57:29.681799+00	2025-08-04 16:57:29.681799+00	2025-08-04 17:07:29.681481+00
42	69cf196717a449d25b990c6c8ab98706	[1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20]	2025-08-04 16:58:01.163314+00	2025-08-04 16:58:01.163314+00	2025-08-04 17:08:01.162771+00
43	0071c5729509f676632307f9b3c9b84f	[1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30]	2025-08-04 16:58:01.166863+00	2025-08-04 16:58:01.166863+00	2025-08-04 17:08:01.164604+00
44	e81ddff7890f3d49c3ad9c904c6d975c	[1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20]	2025-08-04 16:59:13.454555+00	2025-08-04 16:59:13.454555+00	2025-08-04 17:09:13.454287+00
\.


--
-- Data for Name: trip_waypoints; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.trip_waypoints (id, trip_id, location_id, "order", price, is_passed, is_next, passed_timestamp, remaining_time, remaining_distance, is_custom, created_at, updated_at) FROM stdin;
\.


--
-- Data for Name: trips; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.trips (id, route_id, vehicle_id, vehicle, status, departure_time, completion_time, connection_mode, notes, seats, remaining_time_to_destination, remaining_distance_to_destination, is_reversed, current_speed, current_latitude, current_longitude, has_custom_waypoints, created_at, updated_at) FROM stdin;
1	28	1	{"id": 1, "driver": {"name": "Jean Ndayisaba", "phone": "07811111"}, "capacity": 22, "company_id": 2, "company_name": "Rwanda Express", "license_plate": "RAB1111"}	SCHEDULED	1754329804	\N	ONLINE	\N	16	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
2	29	2	{"id": 2, "driver": {"name": "Pierre Uwimana", "phone": "07822222"}, "capacity": 24, "company_id": 3, "company_name": "Kigali Transport", "license_plate": "RAB2222"}	SCHEDULED	1754337004	\N	OFFLINE	\N	17	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
3	30	3	{"id": 3, "driver": {"name": "Marie Mukamana", "phone": "07833333"}, "capacity": 26, "company_id": 4, "company_name": "East Africa Bus", "license_plate": "RAB3333"}	SCHEDULED	1754344204	\N	HYBRID	\N	18	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
4	31	4	{"id": 4, "driver": {"name": "Claude Niyonsenga", "phone": "07844444"}, "capacity": 28, "company_id": 5, "company_name": "Central Transit", "license_plate": "RAB4444"}	SCHEDULED	1754351404	\N	ONLINE	\N	19	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
5	32	5	{"id": 5, "driver": {"name": "Francois Habyarimana", "phone": "07855555"}, "capacity": 30, "company_id": 1, "company_name": "Northern Routes", "license_plate": "RAB5555"}	IN_PROGRESS	1754358604	\N	OFFLINE	\N	20	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
6	33	6	{"id": 6, "driver": {"name": "Joseph Nkurunziza", "phone": "07866666"}, "capacity": 32, "company_id": 2, "company_name": "Southern Express", "license_plate": "RAB6666"}	SCHEDULED	1754365804	\N	HYBRID	\N	21	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
7	34	7	{"id": 7, "driver": {"name": "Paul Kagame", "phone": "07877777"}, "capacity": 34, "company_id": 3, "company_name": "Western Connect", "license_plate": "RAB7777"}	SCHEDULED	1754373004	\N	ONLINE	\N	22	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
8	35	8	{"id": 8, "driver": {"name": "Andre Bizimana", "phone": "07888888"}, "capacity": 36, "company_id": 4, "company_name": "Highland Travel", "license_plate": "RAB8888"}	SCHEDULED	1754380204	\N	OFFLINE	\N	23	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
9	36	9	{"id": 9, "driver": {"name": "Louis Nshimiyimana", "phone": "07899999"}, "capacity": 38, "company_id": 5, "company_name": "Valley Transport", "license_plate": "RAB9999"}	SCHEDULED	1754387404	\N	HYBRID	\N	24	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
10	37	10	{"id": 10, "driver": {"name": "Michel Mugisha", "phone": "07810101010"}, "capacity": 30, "company_id": 1, "company_name": "City Link", "license_plate": "RAB101010"}	IN_PROGRESS	1754394604	\N	ONLINE	\N	25	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
11	38	1	{"id": 1, "driver": {"name": "Philippe Niyongabo", "phone": "07811111111"}, "capacity": 32, "company_id": 2, "company_name": "Rwanda Express", "license_plate": "RAB111111"}	SCHEDULED	1754401804	\N	OFFLINE	\N	26	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
12	39	2	{"id": 2, "driver": {"name": "Jacques Rutaganda", "phone": "07812121212"}, "capacity": 34, "company_id": 3, "company_name": "Kigali Transport", "license_plate": "RAB121212"}	SCHEDULED	1754409004	\N	HYBRID	\N	27	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
13	40	3	{"id": 3, "driver": {"name": "Henri Ntahobari", "phone": "07813131313"}, "capacity": 36, "company_id": 4, "company_name": "East Africa Bus", "license_plate": "RAB131313"}	SCHEDULED	1754416204	\N	ONLINE	\N	28	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
14	41	4	{"id": 4, "driver": {"name": "Robert Ndayambaje", "phone": "07814141414"}, "capacity": 38, "company_id": 5, "company_name": "Central Transit", "license_plate": "RAB141414"}	SCHEDULED	1754423404	\N	OFFLINE	\N	29	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
15	42	5	{"id": 5, "driver": {"name": "Daniel Munyaneza", "phone": "07815151515"}, "capacity": 40, "company_id": 1, "company_name": "Northern Routes", "license_plate": "RAB151515"}	IN_PROGRESS	1754430604	\N	HYBRID	\N	30	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
16	43	6	{"id": 6, "driver": {"name": "Jean Ndayisaba", "phone": "07816161616"}, "capacity": 42, "company_id": 2, "company_name": "Southern Express", "license_plate": "RAB161616"}	SCHEDULED	1754437804	\N	ONLINE	\N	31	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
17	44	7	{"id": 7, "driver": {"name": "Pierre Uwimana", "phone": "07817171717"}, "capacity": 44, "company_id": 3, "company_name": "Western Connect", "license_plate": "RAB171717"}	SCHEDULED	1754445004	\N	OFFLINE	\N	32	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
18	45	8	{"id": 8, "driver": {"name": "Marie Mukamana", "phone": "07818181818"}, "capacity": 46, "company_id": 4, "company_name": "Highland Travel", "license_plate": "RAB181818"}	SCHEDULED	1754452204	\N	HYBRID	\N	33	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
19	46	9	{"id": 9, "driver": {"name": "Claude Niyonsenga", "phone": "07819191919"}, "capacity": 48, "company_id": 5, "company_name": "Valley Transport", "license_plate": "RAB191919"}	SCHEDULED	1754459404	\N	ONLINE	\N	34	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
20	47	10	{"id": 10, "driver": {"name": "Francois Habyarimana", "phone": "07820202020"}, "capacity": 40, "company_id": 1, "company_name": "City Link", "license_plate": "RAB202020"}	IN_PROGRESS	1754466604	\N	OFFLINE	\N	35	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
21	48	1	{"id": 1, "driver": {"name": "Joseph Nkurunziza", "phone": "07821212121"}, "capacity": 42, "company_id": 2, "company_name": "Rwanda Express", "license_plate": "RAB212121"}	SCHEDULED	1754473804	\N	HYBRID	\N	36	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
22	49	2	{"id": 2, "driver": {"name": "Paul Kagame", "phone": "07822222222"}, "capacity": 44, "company_id": 3, "company_name": "Kigali Transport", "license_plate": "RAB222222"}	SCHEDULED	1754481004	\N	ONLINE	\N	37	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
23	50	3	{"id": 3, "driver": {"name": "Andre Bizimana", "phone": "07823232323"}, "capacity": 46, "company_id": 4, "company_name": "East Africa Bus", "license_plate": "RAB232323"}	SCHEDULED	1754488204	\N	OFFLINE	\N	38	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
24	51	4	{"id": 4, "driver": {"name": "Louis Nshimiyimana", "phone": "07824242424"}, "capacity": 48, "company_id": 5, "company_name": "Central Transit", "license_plate": "RAB242424"}	SCHEDULED	1754409004	\N	HYBRID	\N	39	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
25	52	5	{"id": 5, "driver": {"name": "Michel Mugisha", "phone": "07825252525"}, "capacity": 50, "company_id": 1, "company_name": "Northern Routes", "license_plate": "RAB252525"}	IN_PROGRESS	1754416204	\N	ONLINE	\N	40	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
26	53	6	{"id": 6, "driver": {"name": "Philippe Niyongabo", "phone": "07826262626"}, "capacity": 50, "company_id": 2, "company_name": "Southern Express", "license_plate": "RAB262626"}	SCHEDULED	1754423404	\N	OFFLINE	\N	41	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
27	54	7	{"id": 7, "driver": {"name": "Jacques Rutaganda", "phone": "07827272727"}, "capacity": 50, "company_id": 3, "company_name": "Western Connect", "license_plate": "RAB272727"}	SCHEDULED	1754430604	\N	HYBRID	\N	42	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
28	55	8	{"id": 8, "driver": {"name": "Henri Ntahobari", "phone": "07828282828"}, "capacity": 50, "company_id": 4, "company_name": "Highland Travel", "license_plate": "RAB282828"}	SCHEDULED	1754437804	\N	ONLINE	\N	43	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
29	56	9	{"id": 9, "driver": {"name": "Robert Ndayambaje", "phone": "07829292929"}, "capacity": 50, "company_id": 5, "company_name": "Valley Transport", "license_plate": "RAB292929"}	SCHEDULED	1754445004	\N	OFFLINE	\N	44	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
30	57	10	{"id": 10, "driver": {"name": "Daniel Munyaneza", "phone": "07830303030"}, "capacity": 50, "company_id": 1, "company_name": "City Link", "license_plate": "RAB303030"}	IN_PROGRESS	1754452204	\N	HYBRID	\N	45	\N	\N	f	\N	\N	\N	f	2025-08-04 15:50:05.098751+00	2025-08-04 15:50:05.098751+00
\.


--
-- Name: locations_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.locations_id_seq', 48, true);


--
-- Name: route_waypoints_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.route_waypoints_id_seq', 1, false);


--
-- Name: routes_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.routes_id_seq', 62, true);


--
-- Name: sse_sessions_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.sse_sessions_id_seq', 44, true);


--
-- Name: trip_waypoints_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.trip_waypoints_id_seq', 1, false);


--
-- Name: trips_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.trips_id_seq', 30, true);


--
-- Name: locations locations_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.locations
    ADD CONSTRAINT locations_pkey PRIMARY KEY (id);


--
-- Name: route_waypoints route_waypoints_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.route_waypoints
    ADD CONSTRAINT route_waypoints_pkey PRIMARY KEY (id);


--
-- Name: routes routes_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.routes
    ADD CONSTRAINT routes_pkey PRIMARY KEY (id);


--
-- Name: sse_sessions sse_sessions_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.sse_sessions
    ADD CONSTRAINT sse_sessions_pkey PRIMARY KEY (id);


--
-- Name: trip_waypoints trip_waypoints_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.trip_waypoints
    ADD CONSTRAINT trip_waypoints_pkey PRIMARY KEY (id);


--
-- Name: trips trips_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.trips
    ADD CONSTRAINT trips_pkey PRIMARY KEY (id);


--
-- Name: idx_sse_sessions_uuid; Type: INDEX; Schema: public; Owner: postgres
--

CREATE UNIQUE INDEX idx_sse_sessions_uuid ON public.sse_sessions USING btree (uuid);


--
-- Name: route_waypoints fk_route_waypoints_location; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.route_waypoints
    ADD CONSTRAINT fk_route_waypoints_location FOREIGN KEY (location_id) REFERENCES public.locations(id);


--
-- Name: routes fk_routes_destination; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.routes
    ADD CONSTRAINT fk_routes_destination FOREIGN KEY (destination_id) REFERENCES public.locations(id);


--
-- Name: routes fk_routes_origin; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.routes
    ADD CONSTRAINT fk_routes_origin FOREIGN KEY (origin_id) REFERENCES public.locations(id);


--
-- Name: route_waypoints fk_routes_waypoints; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.route_waypoints
    ADD CONSTRAINT fk_routes_waypoints FOREIGN KEY (route_id) REFERENCES public.routes(id);


--
-- Name: trip_waypoints fk_trip_waypoints_location; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.trip_waypoints
    ADD CONSTRAINT fk_trip_waypoints_location FOREIGN KEY (location_id) REFERENCES public.locations(id);


--
-- Name: trips fk_trips_route; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.trips
    ADD CONSTRAINT fk_trips_route FOREIGN KEY (route_id) REFERENCES public.routes(id);


--
-- Name: trip_waypoints fk_trips_waypoints; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.trip_waypoints
    ADD CONSTRAINT fk_trips_waypoints FOREIGN KEY (trip_id) REFERENCES public.trips(id);


--
-- Name: SCHEMA public; Type: ACL; Schema: -; Owner: postgres
--

REVOKE USAGE ON SCHEMA public FROM PUBLIC;


--
-- PostgreSQL database dump complete
--

