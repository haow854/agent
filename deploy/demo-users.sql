-- 线上预置的 5 个演示账号。线上不开放注册，账号只来自这里。
-- MySQL 数据卷为空、第一次初始化时执行一次，排在 01-schema.sql 之后。
-- 这里只有 BCrypt 哈希，密码不在仓库里，由部署的人保管。
-- 邮箱和手机号在表里必填且唯一，填的是占位值。
USE `docagent`;

INSERT INTO `user` (`username`, `password`, `email`, `phone`) VALUES
  ('demo1', '$2a$10$BVABF4fxl92URGf9BW1odur4SRnokOtGs2kKj3MZalUv357bO.Uqa', 'demo1@docagent.local', 'demo1'),
  ('demo2', '$2a$10$v4z9PlgzY.NAzNDgBFHGVe2z/SrCFoMtsaHJ1.gWb8Wj9IkhA/fNe', 'demo2@docagent.local', 'demo2'),
  ('demo3', '$2a$10$mqU3fm11tUrKpJ.E/s8yuujZ7Aal3mxKDpV24M1uUhTRHy.cPgdya', 'demo3@docagent.local', 'demo3'),
  ('demo4', '$2a$10$VCRDxnn3plaeTViK33SRdesBK58m.OkCOhH0RxE83BdP5w7WLCKm2', 'demo4@docagent.local', 'demo4'),
  ('demo5', '$2a$10$f/lY/ii1zM7fve/VeqD6R.JG8QeFTO.uSDhWurbvEYk5By4Z3o1.6', 'demo5@docagent.local', 'demo5');
