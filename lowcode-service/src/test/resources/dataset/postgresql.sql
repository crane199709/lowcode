CREATE TABLE IF NOT EXISTS public.dataset_write_probe (value integer);
GRANT SELECT, INSERT ON public.dataset_write_probe TO tenant_reader;
CREATE OR REPLACE FUNCTION public.dataset_try_write() RETURNS integer
LANGUAGE plpgsql AS $body$
BEGIN
    INSERT INTO public.dataset_write_probe (value) VALUES (1);
    RETURN 1;
END;
$body$;
