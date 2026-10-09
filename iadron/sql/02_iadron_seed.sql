-- Ajustes de IADron sobre los datos de ejemplo de DJI (se ejecuta tras 01_cloud_sample.sql).
USE `cloud_sample`;

-- Workspace: el bind_code es el que se introduce en el Dock / Pilot 2 al vincular con la nube de terceros.
UPDATE `manage_workspace`
SET `workspace_name` = 'IADron Nave',
    `workspace_desc` = 'Pruebas Matrice 3TD + Dock 2 en interior',
    `platform_name`  = 'IADron',
    `bind_code`      = 'IADRON01'
WHERE `workspace_id` = 'e3dea0f5-37f2-4d79-ae58-490af3228069';

-- Usuarios del demo (contraseñas en claro, igual que el original):
--   adminPC / adminPC   -> frontend web y API REST (x-auth-token)
--   pilot   / pilot123  -> login desde Pilot 2 en el RC Plus
-- Cámbialas aquí antes del primer arranque si el servidor no está aislado.
-- UPDATE `manage_user` SET `password` = 'nueva' WHERE `username` = 'adminPC';
-- UPDATE `manage_user` SET `password` = 'nueva' WHERE `username` = 'pilot';
