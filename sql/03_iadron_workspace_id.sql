-- IADron: workspace_id corto.
-- El campo "Org ID" de Pilot 2 (Cloud Service Configuration del Dock 2) corta el UUID original
-- del demo de DJI, y el Dock no se puede vincular. Se renombra a 'iadron-nave' en todas las
-- tablas que lo usan. Se ejecuta tras 01_cloud_sample.sql y 02_iadron_seed.sql (orden alfabético).
USE `cloud_sample`;

SET @viejo = 'e3dea0f5-37f2-4d79-ae58-490af3228069';
SET @nuevo = 'iadron-nave';

UPDATE manage_workspace       SET workspace_id = @nuevo WHERE workspace_id = @viejo;
UPDATE manage_user            SET workspace_id = @nuevo WHERE workspace_id = @viejo;
UPDATE manage_device          SET workspace_id = @nuevo WHERE workspace_id = @viejo;
UPDATE manage_device_firmware SET workspace_id = @nuevo WHERE workspace_id = @viejo;
UPDATE map_group              SET workspace_id = @nuevo WHERE workspace_id = @viejo;
UPDATE media_file             SET workspace_id = @nuevo WHERE workspace_id = @viejo;
UPDATE wayline_file           SET workspace_id = @nuevo WHERE workspace_id = @viejo;
UPDATE wayline_job            SET workspace_id = @nuevo WHERE workspace_id = @viejo;
UPDATE device_flight_area     SET workspace_id = @nuevo WHERE workspace_id = @viejo;
UPDATE flight_area_file       SET workspace_id = @nuevo WHERE workspace_id = @viejo;